package io.vibeminer.infrastructure.buildopening;

import static io.vibeminer.application.buildopening.BuildOpeningException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.*;
import io.vibeminer.application.buildopening.BuildMetadataAdmission.Role;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.protocol.tact.config.TactConfigParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Complete immutable role closure and exact-group acknowledgement share one catalog commit. */
public final class BuildMetadataAdmissionRepository {
  private final SqliteCatalog catalog;
  private final ObjectStore store;
  private final Clock clock;
  private final SqliteBuildLibraryRepository library;

  public BuildMetadataAdmissionRepository(SqliteCatalog catalog, ObjectStore store) {
    this(catalog, store, Clock.systemUTC());
  }

  public BuildMetadataAdmissionRepository(SqliteCatalog catalog, ObjectStore store, Clock clock) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    library = new SqliteBuildLibraryRepository(catalog);
  }

  /** Even absent admission authenticates the registered event/row/sequence before remote work. */
  public Optional<BuildMetadataAdmissionFacts> find(
      BuildLibraryReference reference,
      String locale,
      String scopeRevision,
      CancellationToken token) {
    var id = context(reference, locale, scopeRevision);
    var rows = new ArrayList<Row>();
    String closure;
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      library.requireRegisteredReferenceInTransaction(c, reference, token);
      try (var q = c.prepareStatement("SELECT * FROM build_open_admissions WHERE context_id=?")) {
        q.setString(1, id);
        try (var r = q.executeQuery()) {
          if (!r.next()) {
            c.commit();
            return Optional.empty();
          }
          if (!r.getString("snapshot_id").equals(reference.snapshotId())
              || !r.getString("locale").equals(locale)
              || !r.getString("scope_revision").equals(scopeRevision)
              || !r.getString("classifier_revision")
                  .equals(BuildMetadataDeclarationClassifier.REVISION))
            throw fail(CORRUPT_METADATA, "Admission context or classifier changed");
          closure = r.getString("closure_sha256");
          int expected = r.getInt("required_count");
          try (var role =
              c.prepareStatement(
                  "SELECT * FROM build_open_role_facts WHERE context_id=? ORDER BY role LIMIT"
                      + " 1025")) {
            role.setString(1, id);
            try (var rr = role.executeQuery()) {
              while (rr.next()) rows.add(readRow(rr));
            }
          }
          if (rows.size() != expected || rows.size() > BuildMetadataAdmission.MAXIMUM_REQUIREMENTS)
            throw fail(CORRUPT_METADATA, "Incomplete persisted required metadata closure");
        }
      }
      c.commit();
    } catch (Exception failure) {
      throw mapped(failure);
    }
    var configs = new ArrayList<BuildMetadataAdmissionFacts.ConfigurationFact>();
    var metadata = new ArrayList<BuildMetadataAdmissionFacts.MetadataFact>();
    try (var c = catalog.connect()) {
      for (var row : rows) {
        var alias = alias(c, row.source());
        if (!alias.object().equals(row.object()))
          throw fail(CORRUPT_METADATA, "Role alias object changed");
        if (row.canonical() == null)
          configs.add(new BuildMetadataAdmissionFacts.ConfigurationFact(row.role(), alias));
        else {
          var proof = canonical(c, row.canonical());
          metadata.add(
              new BuildMetadataAdmissionFacts.MetadataFact(
                  row.role(),
                  new ContentSlotKey("wow", proof.contentKey()),
                  row.canonical(),
                  proof));
        }
      }
    } catch (Exception failure) {
      throw mapped(failure);
    }
    try (var leases = leases(configs, metadata, token)) {
      var facts = facts(reference, locale, scopeRevision, configs, metadata, leases, token);
      if (!closure(facts).equals(closure))
        throw fail(CORRUPT_METADATA, "Admission required proof commitment changed");
      // Reread under one short transaction AFTER physical verification; stale proof never wins.
      try (var c = catalog.connect()) {
        c.setAutoCommit(false);
        library.requireRegisteredReferenceInTransaction(c, reference, token);
        validate(c, facts, rows, token);
        for (var row : rows) requireMembership(c, reference.snapshotId(), id, row);
        c.commit();
        return Optional.of(facts);
      }
    } catch (Exception failure) {
      throw mapped(failure);
    }
  }

  /**
   * Ledger freshness only; caller must retain its separately verified source lease. No readiness
   * claim.
   */
  public void validateCurrentAuthority(BuildMetadataAdmissionFacts facts, CancellationToken token) {
    Objects.requireNonNull(facts);
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      validateInTransaction(c, facts, token);
      c.commit();
    } catch (Exception failure) {
      throw mapped(failure);
    }
  }

  /** Validates current SQL authority in the caller transaction; never commits or reads payloads. */
  public void validateInTransaction(
      Connection c, BuildMetadataAdmissionFacts facts, CancellationToken token) {
    Objects.requireNonNull(c);
    Objects.requireNonNull(facts);
    try {
      if (c.getAutoCommit()) throw new IllegalArgumentException("Caller transaction is required");
      library.requireRegisteredReferenceInTransaction(c, facts.reference(), token);
      try (var q =
          c.prepareStatement(
              "SELECT closure_sha256,required_count FROM build_open_admissions WHERE"
                  + " context_id=?")) {
        q.setString(1, context(facts.reference(), facts.locale(), facts.scopeRevision()));
        try (var r = q.executeQuery()) {
          if (!r.next()
              || !r.getString(1).equals(closure(facts))
              || r.getInt(2) != rows(facts).size())
            throw fail(NOT_READY, "Exact complete metadata admission is no longer current");
        }
      }
      var persisted = new ArrayList<Row>();
      try (var q =
          c.prepareStatement(
              "SELECT * FROM build_open_role_facts WHERE context_id=? ORDER BY role LIMIT 1025")) {
        q.setString(1, context(facts.reference(), facts.locale(), facts.scopeRevision()));
        try (var r = q.executeQuery()) {
          while (r.next()) persisted.add(readRow(r));
        }
      }
      validate(c, facts, persisted, token);
      for (var row : persisted)
        requireMembership(
            c,
            facts.reference().snapshotId(),
            context(facts.reference(), facts.locale(), facts.scopeRevision()),
            row);
      token.throwIfCancellationRequested();
    } catch (Exception failure) {
      throw mapped(failure);
    }
  }

  /** Stable immutable closure identity; not an authorization or physical-readiness assertion. */
  public static String closureId(BuildMetadataAdmissionFacts facts) {
    return closure(Objects.requireNonNull(facts));
  }

  public BuildMetadataAdmission commit(BuildMetadataAdmissionFacts facts, CancellationToken token) {
    Objects.requireNonNull(facts);
    Objects.requireNonNull(token);
    try (var leases = leases(facts.configurations(), facts.metadata(), token)) {
      var checked =
          facts(
              facts.reference(),
              facts.locale(),
              facts.scopeRevision(),
              facts.configurations(),
              facts.metadata(),
              leases,
              token);
      if (!checked.admission().equals(facts.admission()))
        throw fail(
            CORRUPT_METADATA, "Caller declaration was not replayed from actual configuration");
      var result = checked.admission();
      var id = context(checked.reference(), checked.locale(), checked.scopeRevision());
      try (var c = catalog.connect()) {
        c.setAutoCommit(false);
        try {
          library.requireRegisteredReferenceInTransaction(c, checked.reference(), token);
          var rows = rows(checked);
          validate(c, checked, rows, token);
          try (var q =
              c.prepareStatement(
                  "INSERT OR IGNORE INTO build_open_admissions VALUES(?,?,?,?,?,?,?,?)")) {
            q.setString(1, id);
            q.setString(2, checked.reference().snapshotId());
            q.setString(3, checked.locale());
            q.setString(4, checked.scopeRevision());
            q.setString(5, BuildMetadataDeclarationClassifier.REVISION);
            q.setInt(6, rows.size());
            q.setString(7, closure(checked));
            q.setString(8, clock.instant().toString());
            q.executeUpdate();
          }
          try (var q =
              c.prepareStatement(
                  "SELECT closure_sha256,required_count FROM build_open_admissions WHERE"
                      + " context_id=?")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              if (!r.next()
                  || !r.getString(1).equals(closure(checked))
                  || r.getInt(2) != rows.size())
                throw fail(CORRUPT_METADATA, "Immutable admission closure conflicts; replan scope");
            }
          }
          for (var row : rows) {
            insertRole(c, id, row);
            attach(c, checked.reference().snapshotId(), id, row);
          }
          // Verify persisted rows too: INSERT OR IGNORE cannot mask a preexisting corrupted role.
          var actual = new ArrayList<Row>();
          try (var q =
              c.prepareStatement(
                  "SELECT * FROM build_open_role_facts WHERE context_id=? ORDER BY role LIMIT"
                      + " 1025")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              while (r.next()) actual.add(readRow(r));
            }
          }
          if (!new HashSet<>(actual).equals(new HashSet<>(rows)))
            throw fail(CORRUPT_METADATA, "Persisted required role facts differ");
          var now = clock.instant();
          try (var q =
              c.prepareStatement("INSERT OR IGNORE INTO build_open_receipts VALUES(?,?,?,?,?)")) {
            q.setString(1, id);
            q.setString(2, checked.reference().eventId().toString());
            q.setInt(3, checked.reference().versionsRowOrdinal());
            q.setString(4, checked.reference().key().identity());
            q.setString(5, now.toString());
            q.executeUpdate();
          }
          library.acknowledgeOpenInTransaction(c, checked.reference(), now, token);
          token.throwIfCancellationRequested();
          c.commit();
          return result;
        } catch (Exception | Error failure) {
          try {
            c.rollback();
          } catch (SQLException rollback) {
            failure.addSuppressed(rollback);
          }
          throw failure;
        }
      }
    } catch (Exception failure) {
      throw mapped(failure);
    }
  }

  private record Row(
      Role role,
      ResolvedSourceIdentity source,
      StoredObject object,
      UUID canonical,
      String proof) {}

  private static List<Row> rows(BuildMetadataAdmissionFacts f) {
    var result = new ArrayList<Row>();
    for (var c : f.configurations())
      result.add(
          new Row(
              c.role(),
              c.originalAlias().source(),
              c.originalAlias().object(),
              null,
              BuildMetadataAdmissionFacts.hash(
                  BuildMetadataAdmissionFacts.configurationBytes(c.originalAlias()))));
    for (var m : f.metadata())
      result.add(
          new Row(
              m.role(),
              m.proof().originalAlias().source(),
              m.proof().object(),
              m.canonicalReservationToken(),
              BuildMetadataAdmissionFacts.hash(CanonicalContentRecords.encode(m.proof()))));
    return List.copyOf(result);
  }

  private static Row readRow(ResultSet r) throws SQLException {
    String canonical = r.getString("canonical_token");
    return new Row(
        Role.valueOf(r.getString("role")),
        new ResolvedSourceIdentity(
            r.getString("namespace"), r.getString("source_key"), r.getString("verifier_semantics")),
        new StoredObject(
            new ObjectDigest(r.getString("object_digest")), r.getLong("object_length")),
        canonical == null ? null : UUID.fromString(canonical),
        r.getString("proof_sha256"));
  }

  private void validate(
      Connection c, BuildMetadataAdmissionFacts f, List<Row> expected, CancellationToken token)
      throws SQLException {
    if (!new HashSet<>(expected).equals(new HashSet<>(rows(f))))
      throw fail(CORRUPT_METADATA, "Required role source/proof changed");
    for (var value : f.configurations()) {
      token.throwIfCancellationRequested();
      if (!alias(c, value.originalAlias().source()).equals(value.originalAlias()))
        throw fail(CORRUPT_METADATA, "Configuration alias evidence changed");
    }
    for (var value : f.metadata()) {
      token.throwIfCancellationRequested();
      var proof = canonical(c, value.canonicalReservationToken());
      if (!proof.equals(value.proof())
          || !proof.contentKey().equals(value.slot().contentKey())
          || !alias(c, proof.originalAlias().source()).equals(proof.originalAlias()))
        throw fail(CORRUPT_METADATA, "Canonical role authority differs from issued facts");
    }
  }

  private static VerifiedSourceAlias alias(Connection c, ResolvedSourceIdentity source)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT a.object_digest,o.length,a.source_url,a.verified_at FROM source_aliases a JOIN"
                + " catalog_objects o ON o.digest=a.object_digest WHERE a.namespace=? AND"
                + " a.source_key=? AND a.verifier_semantics=?")) {
      q.setString(1, source.namespace());
      q.setString(2, source.immutableSourceKey());
      q.setString(3, source.verificationSemantics());
      try (var r = q.executeQuery()) {
        if (!r.next()) throw fail(MISSING_METADATA, "Required durable source alias is missing");
        return new VerifiedSourceAlias(
            source,
            new StoredObject(new ObjectDigest(r.getString(1)), r.getLong(2)),
            new SourceVerificationEvidence(
                source.verificationSemantics(), r.getString(3), Instant.parse(r.getString(4))));
      }
    }
  }

  private static MetadataContentProof canonical(Connection c, UUID token) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT"
                + " namespace,content_key,encoding_key,encoded_bytes,status,representation,length(representation)"
                + " AS proof_length,worker_nonce FROM canonical_content_slots WHERE token=?")) {
      q.setString(1, token.toString());
      try (var r = q.executeQuery()) {
        if (!r.next()
            || !r.getString("status").equals("RETAINED")
            || r.getString("worker_nonce") != null)
          throw fail(NOT_READY, "Required canonical metadata is missing or still owned");
        if (!r.getString("namespace").equals("wow"))
          throw fail(CORRUPT_METADATA, "Unreviewed canonical metadata namespace");
        if (r.getLong("proof_length") < 1
            || r.getLong("proof_length") > CanonicalContentRecords.MAX_BYTES)
          throw fail(CORRUPT_METADATA, "Canonical role proof exceeds bounded codec");
        var bytes = r.getBytes("representation");
        if (!(CanonicalContentRecords.decode(bytes) instanceof MetadataContentProof proof)
            || proof.decodedAttestation().isEmpty()
            || proof.structuralAttestation().isEmpty()
            || !proof.contentKey().equals(r.getString("content_key"))
            || !proof.actualEncodingKey().equals(r.getString("encoding_key"))
            || proof.encodedBytes() != r.getLong("encoded_bytes"))
          throw fail(CORRUPT_METADATA, "Incomplete or mismatched canonical metadata capability");
        return proof;
      }
    }
  }

  private static void insertRole(Connection c, String id, Row r) throws SQLException {
    try (var q =
        c.prepareStatement(
            "INSERT OR IGNORE INTO build_open_role_facts VALUES(?,?,?,?,?,?,?,?,?)")) {
      q.setString(1, id);
      q.setString(2, r.role().name());
      q.setString(3, r.source().namespace());
      q.setString(4, r.source().immutableSourceKey());
      q.setString(5, r.source().verificationSemantics());
      q.setString(6, r.object().digest().value());
      q.setLong(7, r.object().length());
      q.setString(8, r.canonical() == null ? null : r.canonical().toString());
      q.setString(9, r.proof());
      q.executeUpdate();
    }
  }

  private static void attach(Connection c, String snapshot, String context, Row r)
      throws SQLException {
    String role = "BO1_" + context + "_" + r.role().name();
    var alias = alias(c, r.source());
    try (var q =
        c.prepareStatement(
            "INSERT OR IGNORE INTO build_object_memberships VALUES(?,?,?,?,?,?,?)")) {
      q.setString(1, snapshot);
      q.setString(2, role);
      q.setString(3, r.source().namespace());
      q.setString(4, r.source().immutableSourceKey());
      q.setString(5, r.source().verificationSemantics());
      q.setString(6, alias.evidence().sourceUrl());
      q.setString(7, alias.evidence().verifiedAt().toString());
      q.executeUpdate();
    }
    requireMembership(c, snapshot, context, r);
  }

  private static void requireMembership(Connection c, String snapshot, String context, Row r)
      throws SQLException {
    String role = "BO1_" + context + "_" + r.role().name();
    var alias = alias(c, r.source());
    try (var q =
        c.prepareStatement(
            "SELECT namespace,source_key,verifier_semantics,source_url,verified_at FROM"
                + " build_object_memberships WHERE snapshot_id=? AND role=?")) {
      q.setString(1, snapshot);
      q.setString(2, role);
      try (var v = q.executeQuery()) {
        if (!v.next()
            || !v.getString(1).equals(r.source().namespace())
            || !v.getString(2).equals(r.source().immutableSourceKey())
            || !v.getString(3).equals(r.source().verificationSemantics())
            || !v.getString(4).equals(alias.evidence().sourceUrl())
            || !v.getString(5).equals(alias.evidence().verifiedAt().toString()))
          throw fail(CORRUPT_METADATA, "Admission membership conflicts with original evidence");
      }
    }
  }

  private BuildMetadataAdmissionFacts facts(
      BuildLibraryReference ref,
      String locale,
      String scope,
      List<BuildMetadataAdmissionFacts.ConfigurationFact> configs,
      List<BuildMetadataAdmissionFacts.MetadataFact> metadata,
      Leases leases,
      CancellationToken token)
      throws Exception {
    var event =
        new SqliteDiscoveryHistoryRepository(catalog)
            .read(ref.eventId(), token)
            .orElseThrow(() -> fail(MISSING_METADATA, "Exact discovery event is missing"));
    var build =
        event.builds().stream()
            .filter(r -> r.versionsRowOrdinal() == ref.versionsRowOrdinal())
            .findFirst()
            .orElseThrow(() -> fail(CORRUPT_METADATA, "Discovery row missing"))
            .build();
    var config =
        configs.stream()
            .filter(v -> v.role() == Role.BUILD_CONFIG)
            .findFirst()
            .orElseThrow(() -> fail(CORRUPT_METADATA, "Build configuration missing"));
    var declaration =
        new BuildMetadataDeclarationClassifier()
            .classify(
                build,
                config.originalAlias(),
                leases.config(config.originalAlias().object(), token),
                BuildMetadataDeclarationClassifier.Limits.defaults(),
                token);
    var cdn =
        configs.stream()
            .filter(v -> v.role() == Role.CDN_CONFIG)
            .findFirst()
            .orElseThrow(() -> fail(CORRUPT_METADATA, "CDN configuration missing"));
    new TactConfigParser()
        .parse(
            leases.config(cdn.originalAlias().object(), token),
            ref.key().cdnConfig(),
            new TactConfigParser.Limits(
                1 << 20, 16384, 1 << 20, 100000, Long.MAX_VALUE, Long.MAX_VALUE),
            token::isCancellationRequested);
    return new BuildMetadataAdmissionFacts(ref, locale, scope, declaration, configs, metadata);
  }

  private Leases leases(
      List<BuildMetadataAdmissionFacts.ConfigurationFact> configs,
      List<BuildMetadataAdmissionFacts.MetadataFact> metadata,
      CancellationToken token) {
    var result = new Leases();
    try {
      for (var c : configs) result.add(c.originalAlias().object(), token);
      for (var m : metadata) result.add(m.proof().object(), token);
      return result;
    } catch (RuntimeException error) {
      result.close();
      throw error;
    }
  }

  private final class Leases implements AutoCloseable {
    final Map<StoredObject, ObjectReadLease> held = new LinkedHashMap<>();
    final Map<StoredObject, byte[]> configBytes = new HashMap<>();

    void add(StoredObject object, CancellationToken token) {
      token.throwIfCancellationRequested();
      if (held.containsKey(object)) return;
      var lease = store.openRead(object.digest(), token);
      if (!lease.object().equals(object)) {
        lease.close();
        throw fail(CORRUPT_METADATA, "Physical metadata length changed");
      }
      held.put(object, lease);
    }

    byte[] config(StoredObject object, CancellationToken token) throws IOException {
      if (object.length() > 1 << 20)
        throw fail(LIMIT_EXCEEDED, "Configuration exceeds admission byte limit");
      token.throwIfCancellationRequested();
      if (configBytes.containsKey(object)) return configBytes.get(object);
      var bytes = held.get(object).stream().readNBytes((int) object.length() + 1);
      if (bytes.length != object.length())
        throw fail(CORRUPT_METADATA, "Configuration extent changed");
      configBytes.put(object, bytes);
      return bytes;
    }

    public void close() {
      var values = new ArrayList<>(held.values());
      Collections.reverse(values);
      values.forEach(ObjectReadLease::close);
    }
  }

  private static String context(BuildLibraryReference r, String locale, String scope) {
    if (r == null
        || locale == null
        || !locale.matches("[a-z]{2}[A-Z]{2}")
        || scope == null
        || scope.isBlank()
        || scope.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(scope)
        || scope.chars().anyMatch(Character::isISOControl))
      throw fail(INVALID_REQUEST, "Exact bounded opening context required");
    return BuildMetadataAdmissionFacts.hash(
        ("build-open-v1|" + r.snapshotId() + "|" + locale + "|" + scope)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String closure(BuildMetadataAdmissionFacts f) {
    var rows =
        rows(f).stream()
            .sorted(Comparator.comparing(r -> r.role().name()))
            .map(
                r ->
                    r.role()
                        + "|"
                        + r.source()
                        + "|"
                        + r.object()
                        + "|"
                        + r.canonical()
                        + "|"
                        + r.proof())
            .toList();
    return BuildMetadataAdmissionFacts.hash(
        (f.scopeRevision()
                + "|"
                + f.declaration().classifierRevision()
                + "|"
                + f.declaration().rawSha256()
                + "|"
                + String.join("\n", rows))
            .getBytes(StandardCharsets.UTF_8));
  }

  private static BuildOpeningException mapped(Exception e) {
    if (e instanceof BuildOpeningException b) return b;
    if (e instanceof CancellationException cancelled) throw cancelled;
    if (e instanceof ObjectStoreException s)
      return new BuildOpeningException(
          switch (s.code()) {
            case MISSING_OBJECT -> MISSING_METADATA;
            case INTEGRITY_FAILURE -> CORRUPT_METADATA;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case BUSY, STORE_IN_USE -> BUSY;
            case CLOSED -> CLOSED;
            case IO_FAILURE -> STORAGE_UNAVAILABLE;
          },
          "Required local metadata failed physical verification",
          s);
    if (e instanceof BuildLibraryException s)
      return new BuildOpeningException(
          switch (s.code()) {
            case INVALID_REQUEST -> INVALID_REQUEST;
            case BUSY -> BUSY;
            case CLOSED -> CLOSED;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
            case NOT_FOUND -> NOT_READY;
            default -> CORRUPT_METADATA;
          },
          "Exact registered opening context is unavailable",
          s);
    if (e instanceof SQLException s)
      return new BuildOpeningException(
          s.getErrorCode() == 5 ? BUSY : STORAGE_UNAVAILABLE,
          "Build admission transaction failed",
          s);
    return new BuildOpeningException(
        CORRUPT_METADATA, "Build admission evidence failed validation", e);
  }

  private static BuildOpeningException fail(BuildOpeningException.Code code, String message) {
    return new BuildOpeningException(code, message);
  }
}
