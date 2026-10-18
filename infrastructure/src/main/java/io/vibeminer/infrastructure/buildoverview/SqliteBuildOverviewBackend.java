package io.vibeminer.infrastructure.buildoverview;

import static io.vibeminer.application.buildoverview.BuildOverview.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildopening.BuildOpeningException;
import io.vibeminer.application.buildoverview.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionFacts;
import io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionRepository;
import io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionSourceAssociations;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.encodedsource.OfficialEncodedSourcePublisher;
import io.vibeminer.infrastructure.inventory.SqliteBuildRetentionInventory;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import org.sqlite.ProgressHandler;

/** Read-only exact-build assessment. Cold metadata replay has its own admitted source bounds. */
public final class SqliteBuildOverviewBackend implements BuildOverviewBackend {
  private final SqliteCatalog catalog;
  private final BuildMetadataAdmissionRepository admissions;
  private final SqliteBuildRetentionInventory inventory;
  private final OfficialEncodedSourcePublisher encoded;
  private final EncodedSourcePlan.Limits encodedLimits;
  private final SqliteBuildObjectCatalog aliases;
  private final Clock clock;

  public SqliteBuildOverviewBackend(
      SqliteCatalog catalog,
      ObjectStore store,
      BuildMetadataAdmissionRepository admissions,
      SqliteBuildRetentionInventory inventory,
      OfficialEncodedSourcePublisher encoded,
      EncodedSourcePlan.Limits encodedLimits,
      Clock clock) {
    this.catalog = Objects.requireNonNull(catalog);
    this.admissions = Objects.requireNonNull(admissions);
    this.inventory = Objects.requireNonNull(inventory);
    this.encoded = Objects.requireNonNull(encoded);
    this.encodedLimits = Objects.requireNonNull(encodedLimits);
    this.clock = Objects.requireNonNull(clock);
    aliases = new SqliteBuildObjectCatalog(catalog, Objects.requireNonNull(store));
  }

  @Override
  public Snapshot read(Request request, ProgressSink progress, CancellationToken cancellation) {
    try {
      return readBounded(request, progress, cancellation);
    } catch (WorkLimit failure) {
      throw new BuildOverviewException(
          BuildOverviewException.Code.LIMIT, "Overview work limit reached", failure);
    } catch (ArithmeticException failure) {
      throw new BuildOverviewException(
          BuildOverviewException.Code.LIMIT, "Overview totals exceed supported bounds", failure);
    }
  }

  private Snapshot readBounded(
      Request request, ProgressSink progress, CancellationToken cancellation) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(progress);
    Objects.requireNonNull(cancellation);
    var work = new Work(request, cancellation);
    var problems = new ArrayList<Problem>();
    work.check();
    if (request.target().isEmpty()) {
      try {
        var facts =
            admissions.find(
                request.reference(), request.locale(), request.scopeRevision(), work.token());
        work.check();
        if (facts.isEmpty())
          return finish(
              request,
              progress,
              MetadataState.NOT_READY,
              InventoryState.UNKNOWN,
              Measurements.unknown(),
              Optional.empty(),
              List.of(
                  new Problem(
                      ProblemCode.METADATA_MISSING,
                      "Complete required metadata has not been admitted")),
              work);
        return finish(
            request,
            progress,
            MetadataState.READY,
            InventoryState.UNKNOWN,
            Measurements.unknown(),
            Optional.empty(),
            List.of(),
            work);
      } catch (BuildOpeningException failure) {
        return metadataFailure(request, progress, failure, work);
      }
    }
    var target = request.target().orElseThrow();
    try (var opened = inventory.openSealed(target, work.token())) {
      long total = opened.seal().memberCount();
      String generation;
      OptionalLong variants;
      try (var c = catalog.connect()) {
        c.setAutoCommit(false);
        work.install(c);
        inventory.validateConsumerInTransaction(
            c,
            opened,
            request.reference(),
            request.locale(),
            request.scopeRevision(),
            work.token());
        try (var q =
            c.prepareStatement(
                "SELECT generation FROM build_retention_bindings WHERE inventory_id=? AND"
                    + " issuance=?")) {
          q.setString(1, target.inventoryId());
          q.setLong(2, target.inventoryGeneration());
          try (var r = q.executeQuery()) {
            if (!r.next()) throw changed();
            generation = r.getString(1);
          }
        }
        try (var q =
            c.prepareStatement(
                "SELECT expected_rows FROM build_inventory_sources WHERE generation=? AND"
                    + " role='ROOT' AND complete=1")) {
          q.setString(1, generation);
          try (var r = q.executeQuery()) {
            variants = r.next() ? OptionalLong.of(nonnegative(r, 1)) : OptionalLong.empty();
          }
        }
        c.rollback();
      }
      progress.update(
          new Progress(Stage.READING_INVENTORY, 0, OptionalLong.of(total), 0, Optional.empty()));
      long planned = 0, retained = 0, retainedBytes = 0;
      boolean complete = true;
      var original = digest();
      try {
        if (total > request.limits().maximumMembers()) throw new WorkLimit();
        for (long ordinal = 0; ordinal < total; ) {
          work.check();
          var page =
              opened.members(
                  ordinal, (int) Math.min(128, request.limits().pageSize()), work.token());
          if (page.isEmpty()) throw changed();
          var facts = readPage(request, opened, page, work);
          for (int i = 0; i < page.size(); i++) {
            var member = page.get(i);
            var fact = facts.get(i);
            planned = Math.addExact(planned, member.encodedBytes());
            original.update(fact.commitment());
            if (fact.proof() != null) {
              long charge = Math.multiplyExact(member.encodedBytes(), 2);
              work.charge(charge);
              try {
                if (fact.proof() instanceof EncodedContentProof value)
                  encoded.inspect(value.receipt(), encodedLimits, work.token());
                // Metadata proof is checked against this admitted closure by readPage; openSealed
                // holds its fully replayed source lease. It is never converted to an encoded
                // receipt.
                retained++;
                retainedBytes = Math.addExact(retainedBytes, member.encodedBytes());
              } catch (EncodedSourceException failure) {
                work.check();
                if (failure.code() == EncodedSourceException.Code.BUDGET_EXCEEDED)
                  throw new WorkLimit();
                if (failure.code() == EncodedSourceException.Code.STORAGE_UNAVAILABLE)
                  throw new BuildOverviewException(
                      BuildOverviewException.Code.STORAGE,
                      "Retained source storage unavailable",
                      failure);
                if (failure.code() == EncodedSourceException.Code.CLOSED
                    || failure.code() == EncodedSourceException.Code.BUSY)
                  throw new BuildOverviewException(
                      BuildOverviewException.Code.BUSY,
                      "Retained source verifier unavailable",
                      failure);
                add(
                    problems,
                    failure.code() == EncodedSourceException.Code.MISSING_LOCAL
                        ? ProblemCode.RETAINED_MISSING
                        : ProblemCode.RETAINED_CORRUPT,
                    "Retained source failed current physical verification");
              }
            } else
              add(
                  problems,
                  fact.problem(),
                  "Current exact source membership is not verified retained");
            work.checked++;
            progress.update(
                new Progress(
                    Stage.CHECKING_RETAINED,
                    work.checked,
                    OptionalLong.of(total),
                    work.bytes,
                    Optional.empty()));
          }
          ordinal = Math.addExact(ordinal, page.size());
        }
        // Recheck mutable canonical/source facts after the physical pass, in bounded pages. No
        // receipt map or duplicated payload/cache is retained between passes.
        var current = digest();
        for (long ordinal = 0; ordinal < total; ) {
          var page =
              opened.members(
                  ordinal, (int) Math.min(128, request.limits().pageSize()), work.token());
          if (page.isEmpty()) throw changed();
          for (var fact : readPage(request, opened, page, work)) current.update(fact.commitment());
          ordinal += page.size();
        }
        work.sourceCommitment = HexFormat.of().formatHex(current.digest());
        if (!Arrays.equals(original.digest(), HexFormat.of().parseHex(work.sourceCommitment))) {
          complete = false;
          add(problems, ProblemCode.SOURCE_CHANGED, "Source facts changed during assessment");
        }
      } catch (SQLException failure) {
        if (!work.exhausted) throw failure;
        work.cancellation.throwIfCancellationRequested();
        complete = false;
        add(problems, ProblemCode.LIMIT, "Explicit overview SQL assessment limit reached");
      } catch (WorkLimit failure) {
        complete = false;
        add(problems, ProblemCode.LIMIT, "Explicit overview assessment limit reached");
      }
      // Planned bytes are complete only after all membership rows were enumerated.
      boolean denominator = work.checked == total;
      var measurements =
          new Measurements(
              OptionalLong.empty(),
              variants,
              OptionalLong.of(total),
              denominator ? OptionalLong.of(planned) : OptionalLong.empty(),
              complete ? OptionalLong.of(retained) : OptionalLong.empty(),
              complete ? OptionalLong.of(retainedBytes) : OptionalLong.empty(),
              denominator,
              complete,
              OptionalLong.empty(),
              OptionalLong.empty(),
              OptionalLong.empty());
      Optional<Retention> retention;
      try {
        retention = retention(request, opened, work);
      } catch (WorkLimit failure) {
        retention = Optional.empty();
        add(
            problems,
            ProblemCode.RETENTION_STATUS_UNAVAILABLE,
            "Retention ledger observation exceeded remaining assessment budget");
      }
      return finish(
          request,
          progress,
          MetadataState.READY,
          InventoryState.SEALED,
          measurements,
          retention,
          problems,
          work);
    } catch (BuildOpeningException failure) {
      return metadataFailure(request, progress, failure, work);
    } catch (WorkLimit failure) {
      throw new BuildOverviewException(
          BuildOverviewException.Code.LIMIT,
          "Overview authority assessment limit reached",
          failure);
    } catch (SQLException failure) {
      work.cancellation.throwIfCancellationRequested();
      if (work.exhausted)
        throw new BuildOverviewException(
            BuildOverviewException.Code.LIMIT, "Overview SQL limit reached", failure);
      throw new BuildOverviewException(
          BuildOverviewException.Code.STORAGE, "Cannot read current build overview", failure);
    }
  }

  private List<Fact> readPage(
      Request request,
      BuildRetentionInventory.Opened opened,
      List<BuildRetention.Member> members,
      Work work)
      throws SQLException {
    var out = new ArrayList<Fact>();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      work.install(c);
      inventory.validateConsumerInTransaction(
          c, opened, request.reference(), request.locale(), request.scopeRevision(), work.token());
      for (var member : members) {
        work.check();
        var hash = digest();
        CanonicalContentProof proof = null;
        ProblemCode problem = ProblemCode.RETAINED_MISSING;
        var slot = new ContentSlotKey("wow", member.contentKey());
        try {
          CanonicalContentGuards.check(c, slot);
          try (var q =
              c.prepareStatement(
                  "SELECT"
                      + " encoding_key,encoded_bytes,token,status,worker_nonce,length(representation),representation"
                      + " FROM canonical_content_slots WHERE namespace=? AND content_key=?")) {
            q.setString(1, slot.namespace());
            q.setString(2, slot.contentKey());
            try (var r = q.executeQuery()) {
              if (r.next()) {
                for (int n = 1; n <= 6; n++) framed(hash, String.valueOf(r.getObject(n)));
                if (!member.encodingKey().equals(r.getString(1))
                    || nonnegative(r, 2) != member.encodedBytes())
                  problem = ProblemCode.CANONICAL_CONFLICT;
                else if (r.getString(4).equals("RETAINED") && r.getString(5) == null) {
                  if (nonnegative(r, 6) > CanonicalContentRecords.MAX_BYTES)
                    throw new IllegalArgumentException("Oversized canonical proof");
                  byte[] raw = r.getBytes(7);
                  hash.update(raw);
                  var candidate = CanonicalContentRecords.decode(raw);
                  if (!candidate.contentKey().equals(member.contentKey())
                      || !candidate.actualEncodingKey().equals(member.encodingKey())
                      || candidate.encodedBytes() != member.encodedBytes())
                    throw new IllegalArgumentException("Canonical tuple changed");
                  var alias =
                      candidate instanceof EncodedContentProof e
                          ? e.receipt().encoded()
                          : ((MetadataContentProof) candidate).originalAlias();
                  if (!aliases
                      .findAliasInTransaction(c, alias.source())
                      .filter(alias::equals)
                      .isPresent())
                    throw new IllegalArgumentException("Current source alias changed");
                  if (candidate instanceof EncodedContentProof e) {
                    if (!e.receipt().slot().equals(slot))
                      throw new IllegalArgumentException("Canonical namespace changed");
                    verifyOrigin(c, e);
                    try (var s =
                        c.prepareStatement(
                            "SELECT length(record),record,fulfillment_proof FROM"
                                + " build_retention_sources WHERE target_id=? AND member_id=?")) {
                      s.setString(1, opened.seal().target().identity());
                      s.setString(2, member.memberId());
                      try (var rows = s.executeQuery()) {
                        if (rows.next()) {
                          if (nonnegative(rows, 1) > 131072)
                            throw new IllegalArgumentException("Oversized source membership");
                          var record = rows.getBytes(2);
                          hash.update(record);
                          framed(hash, rows.getString(3));
                          SqliteBuildRetentionSourceAssociations.verifyRecord(
                              record,
                              rows.getString(3),
                              opened.seal().target(),
                              member,
                              UUID.fromString(r.getString(3)),
                              e.receipt());
                          proof = candidate;
                        }
                      }
                    }
                  } else {
                    try (var s =
                        c.prepareStatement(
                            "SELECT f.proof_sha256,f.role FROM build_open_role_facts f JOIN"
                                + " build_open_admissions a ON a.context_id=f.context_id WHERE"
                                + " a.closure_sha256=? AND a.snapshot_id=? AND a.locale=? AND"
                                + " a.scope_revision=? AND f.canonical_token=? AND"
                                + " f.object_digest=? AND f.object_length=?")) {
                      s.setString(1, opened.seal().target().admissionId());
                      s.setString(2, request.reference().snapshotId());
                      s.setString(3, request.locale());
                      s.setString(4, request.scopeRevision());
                      s.setString(5, r.getString(3));
                      s.setString(6, candidate.object().digest().value());
                      s.setLong(7, candidate.encodedBytes());
                      try (var rows = s.executeQuery()) {
                        if (rows.next()
                            && rows.getString(1)
                                .equals(HexFormat.of().formatHex(digest().digest(raw)))) {
                          var fact =
                              new BuildMetadataAdmissionFacts.MetadataFact(
                                  BuildMetadataAdmission.Role.valueOf(rows.getString(2)),
                                  slot,
                                  UUID.fromString(r.getString(3)),
                                  (MetadataContentProof) candidate);
                          framed(
                              hash,
                              SqliteBuildRetentionSourceAssociations.metadataFulfillmentProof(
                                  opened.seal().target(), member, fact));
                          proof = candidate;
                          if (rows.next())
                            throw new IllegalArgumentException(
                                "Ambiguous admitted metadata member");
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        } catch (IllegalArgumentException failure) {
          problem = ProblemCode.RETAINED_CORRUPT;
        } catch (io.vibeminer.application.content.ContentClaimException failure) {
          problem = ProblemCode.CANONICAL_CONFLICT;
        }
        framed(hash, member.memberId());
        framed(hash, proof == null ? problem.name() : "VERIFIED");
        out.add(new Fact(proof, problem, hash.digest()));
      }
      c.rollback();
    }
    return out;
  }

  private void verifyOrigin(Connection c, EncodedContentProof candidate) throws SQLException {
    var value = candidate.receipt();
    var source = value.encoded().source();
    try (var q =
        c.prepareStatement(
            "SELECT length(record),record FROM encoded_file_receipts WHERE namespace=? AND"
                + " encoding_key=? AND verifier_semantics=?")) {
      q.setString(1, source.namespace());
      q.setString(2, source.immutableSourceKey());
      q.setString(3, source.verificationSemantics());
      try (var r = q.executeQuery()) {
        if (!r.next() || nonnegative(r, 1) > CanonicalContentRecords.MAX_BYTES)
          throw new IllegalArgumentException("Missing original encoded proof");
        if (!(CanonicalContentRecords.decode(r.getBytes(2)) instanceof EncodedContentProof origin)
            || !value.equals(
                new io.vibeminer.domain.selectedfile.ContentRepresentation(
                    value.slot(),
                    origin.receipt().encoded(),
                    origin.receipt().scope(),
                    origin.receipt().sourceReceipt())))
          throw new IllegalArgumentException("Original encoded proof changed");
      }
    }
  }

  private Optional<Retention> retention(
      Request request, BuildRetentionInventory.Opened opened, Work work) throws SQLException {
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      work.install(c);
      inventory.validateConsumerInTransaction(
          c, opened, request.reference(), request.locale(), request.scopeRevision(), work.token());
      try (var q =
          c.prepareStatement(
              "SELECT mode,generation,next_ordinal,member_count FROM build_retention_intents WHERE"
                  + " target_id=?")) {
        q.setString(1, opened.seal().target().identity());
        try (var r = q.executeQuery()) {
          if (!r.next()) return Optional.empty();
          var mode = BuildRetention.Mode.valueOf(r.getString(1));
          long generation = nonnegative(r, 2);
          long nextOrdinal = nonnegative(r, 3), memberCount = nonnegative(r, 4);
          if (nextOrdinal > memberCount)
            throw new IllegalArgumentException("Invalid retention enumeration position");
          var counts = new long[4];
          try (var s =
              c.prepareStatement(
                  "SELECT state,count(*) FROM build_retention_jobs WHERE target_id=? GROUP BY"
                      + " state")) {
            s.setString(1, opened.seal().target().identity());
            try (var rows = s.executeQuery()) {
              while (rows.next())
                counts[BuildRetention.State.valueOf(rows.getString(1)).ordinal()] =
                    nonnegative(rows, 2);
            }
          }
          return Optional.of(
              new Retention(
                  mode,
                  generation,
                  OptionalLong.of(counts[0]),
                  OptionalLong.of(counts[1]),
                  OptionalLong.of(counts[2]),
                  OptionalLong.of(counts[3]),
                  nextOrdinal == memberCount));
        }
      }
    }
  }

  private Snapshot metadataFailure(
      Request request, ProgressSink progress, BuildOpeningException failure, Work work) {
    work.check();
    var code =
        switch (failure.code()) {
          case MISSING_METADATA, NOT_READY -> ProblemCode.METADATA_MISSING;
          case UNSUPPORTED_METADATA -> ProblemCode.UNSUPPORTED;
          case LIMIT_EXCEEDED -> ProblemCode.LIMIT;
          default -> ProblemCode.METADATA_CORRUPT;
        };
    return finish(
        request,
        progress,
        MetadataState.NEEDS_ATTENTION,
        InventoryState.NEEDS_ATTENTION,
        Measurements.unknown(),
        Optional.empty(),
        List.of(new Problem(code, "Required metadata cannot currently be verified")),
        work);
  }

  private Snapshot finish(
      Request request,
      ProgressSink progress,
      MetadataState metadata,
      InventoryState inventoryState,
      Measurements values,
      Optional<Retention> retention,
      List<Problem> problems,
      Work work) {
    work.cancellation.throwIfCancellationRequested();
    var readiness =
        metadata != MetadataState.READY
            ? Readiness.NOT_READY
            : values.verificationComplete()
                    && values.acquisitionUnits().equals(values.retainedUnits())
                    && values.plannedEncodedBytes().equals(values.retainedScopeEncodedBytes())
                    && problems.stream().noneMatch(Problem::blocksFull)
                ? Readiness.FULL
                : Readiness.PARTIAL;
    var hash = digest();
    framed(hash, request.reference().toString());
    framed(hash, request.locale());
    framed(hash, request.scopeRevision());
    framed(hash, request.target().map(BuildRetention.Target::identity).orElse("unissued"));
    framed(hash, work.sourceCommitment);
    framed(hash, values.toString());
    framed(hash, retention.toString());
    framed(hash, problems.toString());
    var result =
        new Snapshot(
            request.reference(),
            request.locale(),
            request.scopeRevision(),
            HexFormat.of().formatHex(hash.digest()),
            clock.instant(),
            metadata,
            inventoryState,
            readiness,
            request.target(),
            values,
            retention,
            problems);
    progress.update(
        new Progress(
            Stage.COMPLETE,
            work.checked,
            values.acquisitionUnits(),
            work.bytes,
            Optional.of(result)));
    return result;
  }

  private record Fact(CanonicalContentProof proof, ProblemCode problem, byte[] commitment) {}

  private static void add(List<Problem> problems, ProblemCode code, String message) {
    if (problems.stream().noneMatch(p -> p.code() == code))
      problems.add(new Problem(code, message));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static void framed(MessageDigest digest, String value) {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static long nonnegative(ResultSet rows, int index) throws SQLException {
    Object value = rows.getObject(index);
    if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0)
      throw new IllegalArgumentException("Invalid typed nonnegative SQL value");
    return ((Number) value).longValue();
  }

  private static BuildOverviewException changed() {
    return new BuildOverviewException(
        BuildOverviewException.Code.CORRUPT_RESULT, "Exact inventory authority changed");
  }

  private static final class WorkLimit extends RuntimeException {}

  private static final class Work {
    final Request request;
    final CancellationToken cancellation;
    final long start = System.nanoTime();
    long instructions, bytes, checked;
    String sourceCommitment = "unassessed";
    boolean exhausted;

    Work(Request request, CancellationToken cancellation) {
      this.request = request;
      this.cancellation = cancellation;
    }

    CancellationToken token() {
      return () -> {
        check();
        return false;
      };
    }

    void check() {
      cancellation.throwIfCancellationRequested();
      if (exhausted || System.nanoTime() - start >= request.limits().maximumDuration().toNanos())
        throw new WorkLimit();
    }

    void charge(long amount) {
      check();
      if (amount > request.limits().maximumPayloadVerificationBytes() - bytes)
        throw new WorkLimit();
      bytes += amount;
    }

    void install(Connection c) throws SQLException {
      ProgressHandler.setHandler(
          c,
          100,
          new ProgressHandler() {
            @Override
            protected int progress() {
              instructions += 100;
              if (cancellation.isCancellationRequested()
                  || instructions > request.limits().maximumSqlInstructions()
                  || System.nanoTime() - start >= request.limits().maximumDuration().toNanos()) {
                exhausted = true;
                return 1;
              }
              return 0;
            }
          });
    }
  }
}
