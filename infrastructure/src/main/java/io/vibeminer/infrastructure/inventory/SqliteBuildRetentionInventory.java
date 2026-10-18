package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.application.buildretention.BuildRetention.*;
import static io.vibeminer.application.buildretention.BuildRetentionException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.buildopening.BuildMetadataAdmission.Role;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import io.vibeminer.infrastructure.discoveryhistory.SqliteDiscoveryHistoryRepository;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/**
 * Sealed membership authority. Locator ceilings are reservations, never availability or wire facts.
 * Issued scope identity excludes observation receipts; its first regional provenance stays
 * immutable.
 */
public final class SqliteBuildRetentionInventory
    implements io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionRepository
            .TransactionalInventory,
        AutoCloseable {
  public record ReservationPolicy(SelectedFileBudgets payload, ArchiveSearchBudgets archives) {
    public ReservationPolicy {
      Objects.requireNonNull(payload);
      Objects.requireNonNull(archives);
    }

    /** Versioned policy identity only; grants no source authority or physical readiness. */
    public String commitment() {
      return hash(
          "retention-locator-ceilings-v1",
          payload.lookup().toString(),
          Long.toString(payload.maximumEncodedFileBytes()),
          Integer.toString(payload.maximumPayloadRangeBytes()),
          Integer.toString(payload.maximumPayloadRequests()),
          Long.toString(payload.maximumPayloadWireBytes()),
          payload.maximumPayloadDuration().toString(),
          Integer.toString(archives.maximumArchives()),
          Long.toString(archives.maximumWireBytes()),
          Integer.toString(archives.maximumRequests()),
          archives.maximumDuration().toString(),
          Long.toString(archives.maximumArchiveBytes()));
    }

    Cost cost(long bytes) {
      if (bytes < 1) throw fail(CORRUPT_STATE, "Positive complete encoded member size required");
      try {
        long requests = Math.addExact(1, (bytes - 1) / payload.maximumPayloadRangeBytes());
        if (bytes > payload.maximumEncodedFileBytes()
            || bytes > payload.maximumPayloadWireBytes()
            || requests > payload.maximumPayloadRequests())
          throw fail(LIMIT_EXCEEDED, "Member exceeds bounded locator/payload policy");
        return new Cost(
            Math.addExact(bytes, archives.maximumWireBytes()),
            bytes,
            Math.addExact(Math.addExact(requests, 1), archives.maximumRequests()));
      } catch (ArithmeticException overflow) {
        throw new BuildRetentionException(
            LIMIT_EXCEEDED, "Reservation ceiling exceeds supported integer range", overflow);
      }
    }
  }

  private record Binding(
      Target target,
      String generation,
      UUID event,
      long sequence,
      int row,
      String locale,
      String metadataScope,
      String policy,
      long count) {}

  private final SqliteCatalog catalog;
  private final ObjectStore store;
  private final BuildMetadataAdmissionRepository admissions;
  private final SqliteEncodingLookupIndex encoding;
  private final MetadataStreamBudgets budgets;
  private final SqliteEncodingLookupIndex.Limits limits;
  private final ReservationPolicy policy;
  private final int maximumContexts;
  private final Object monitor = new Object();
  private final LinkedHashMap<String, Context> contexts = new LinkedHashMap<>(4, .75f, true);
  private int initializing;
  private boolean closed;

  public SqliteBuildRetentionInventory(
      SqliteCatalog catalog,
      ObjectStore store,
      BuildMetadataAdmissionRepository admissions,
      SqliteEncodingLookupIndex encoding,
      MetadataStreamBudgets budgets,
      SqliteEncodingLookupIndex.Limits limits,
      ReservationPolicy policy) {
    this(catalog, store, admissions, encoding, budgets, limits, policy, 2);
  }

  /** Contexts retain source leases/receipts, never decoded payload buffers or new executors. */
  public SqliteBuildRetentionInventory(
      SqliteCatalog catalog,
      ObjectStore store,
      BuildMetadataAdmissionRepository admissions,
      SqliteEncodingLookupIndex encoding,
      MetadataStreamBudgets budgets,
      SqliteEncodingLookupIndex.Limits limits,
      ReservationPolicy policy,
      int maximumContexts) {
    if (maximumContexts < 1 || maximumContexts > 16)
      throw new IllegalArgumentException("Verified context capacity must be1..16");
    this.maximumContexts = maximumContexts;
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
    this.admissions = Objects.requireNonNull(admissions);
    this.encoding = Objects.requireNonNull(encoding);
    this.budgets = Objects.requireNonNull(budgets);
    this.limits = Objects.requireNonNull(limits);
    this.policy = Objects.requireNonNull(policy);
  }

  public Target issueTarget(
      BuildLibraryReference reference,
      String locale,
      String metadataScope,
      SqliteBuildInventory.Prepared prepared,
      CancellationToken token) {
    Objects.requireNonNull(reference);
    Objects.requireNonNull(prepared);
    token.throwIfCancellationRequested();
    synchronized (monitor) {
      if (closed) throw fail(NOT_READY, "Inventory authority closed");
    }
    if (!SqliteBuildInventory.REVISION.equals(prepared.revision()))
      throw fail(NOT_READY, "Unsupported inventory revision");
    UUID.fromString(prepared.generation());
    var facts =
        admissions
            .find(reference, locale, metadataScope, token)
            .orElseThrow(() -> fail(NOT_READY, "Complete metadata admission required"));
    try (var en = encoding.open(reference, locale, metadataScope, budgets, limits, token);
        var held = new HeldSources(facts, token)) {
      return transaction(
          c -> {
            validate(c, facts, en, prepared.generation(), prepared.uniqueContentTargets(), token);
            String admission = BuildMetadataAdmissionRepository.closureId(facts);
            String scope =
                hash(
                    "retention-scope-v1",
                    prepared.generation(),
                    admission,
                    en.artifactId(),
                    en.generation(),
                    prepared.revision(),
                    locale,
                    metadataScope,
                    policy.commitment());
            String id =
                hash(
                    "retention-issuance-v1",
                    reference.key().identity(),
                    reference.snapshotId(),
                    scope);
            try (var q =
                c.prepareStatement(
                    "INSERT OR IGNORE INTO"
                        + " build_retention_bindings(inventory_id,group_id,snapshot_id,admission_id,generation,scope_revision,scope_commitment,event_id,observation_sequence,row_ordinal,locale,metadata_scope,policy,member_count)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
              Object[] values = {
                id,
                reference.key().identity(),
                reference.snapshotId(),
                admission,
                prepared.generation(),
                prepared.revision(),
                scope,
                reference.eventId().toString(),
                reference.observationSequence(),
                reference.versionsRowOrdinal(),
                locale,
                metadataScope,
                policy.commitment(),
                prepared.uniqueContentTargets()
              };
              for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
              q.executeUpdate();
            }
            var b = read(c, id);
            if (!b.generation.equals(prepared.generation())
                || b.count != prepared.uniqueContentTargets())
              throw fail(CORRUPT_STATE, "Conflicting immutable target issuance");
            return b.target;
          });
    } catch (IOException e) {
      throw new BuildRetentionException(STORAGE_UNAVAILABLE, "Cannot verify inventory sources", e);
    }
  }

  @Override
  public Opened openSealed(Target target, CancellationToken token) {
    Objects.requireNonNull(target);
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    Binding binding =
        transaction(
            c -> {
              var b = read(c, target.inventoryId());
              if (!b.target.equals(target))
                throw fail(STALE_GENERATION, "Target differs from durable issuance");
              return b;
            });
    if (!binding.policy.equals(policy.commitment()))
      throw fail(STALE_GENERATION, "Locator reservation policy changed");
    Context evicted = null;
    Context cached = null;
    synchronized (monitor) {
      if (closed) throw fail(NOT_READY, "Inventory authority closed");
      var hit = contexts.get(target.identity());
      if (hit != null) {
        hit.borrowers++;
        cached = hit;
      }
      if (cached == null) {
        if (initializing >= 2) throw fail(BUSY, "Two inventory contexts are already initializing");
        if (contexts.size() + initializing >= maximumContexts) {
          for (var it = contexts.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().borrowers == 0) {
              evicted = e.getValue();
              it.remove();
              break;
            }
          }
          if (evicted == null) throw fail(BUSY, "Verified inventory context capacity is active");
        }
        initializing++;
      }
    }
    if (cached != null) {
      var borrowed = new Borrow(cached);
      try {
        var verified = cached;
        transaction(
            c -> {
              validateContext(c, verified, token);
              return null;
            });
        return borrowed;
      } catch (Throwable failure) {
        closeOnFailure(borrowed, failure);
        throw failure;
      }
    }
    Context created = null;
    boolean reservationHeld = true;
    Throwable primaryFailure = null;
    try {
      if (evicted != null) evicted.close();
      var event =
          new SqliteDiscoveryHistoryRepository(catalog)
              .read(binding.event, token)
              .orElseThrow(() -> fail(CORRUPT_STATE, "Issuance observation missing"));
      var ref =
          BuildLibraryReference.from(event, binding.sequence).stream()
              .filter(r -> r.versionsRowOrdinal() == binding.row)
              .findFirst()
              .orElseThrow(() -> fail(CORRUPT_STATE, "Issuance row missing"));
      if (!ref.key().identity().equals(target.groupId())
          || !ref.snapshotId().equals(target.snapshotId()))
        throw fail(CORRUPT_STATE, "Issuance reference changed");
      var facts =
          admissions
              .find(ref, binding.locale, binding.metadataScope, token)
              .orElseThrow(() -> fail(NOT_READY, "Complete metadata admission missing"));
      var en = encoding.open(ref, binding.locale, binding.metadataScope, budgets, limits, token);
      try {
        created = new Context(binding, facts, en, new HeldSources(facts, token));
      } catch (Throwable e) {
        closeOnFailure(en, e);
        throw e;
      }
      var ready = created;
      transaction(
          c -> {
            validateContext(c, ready, token);
            return null;
          });
      Context selected;
      Context redundant = null;
      synchronized (monitor) {
        initializing--;
        reservationHeld = false;
        if (closed) throw fail(NOT_READY, "Inventory authority closed");
        var winner = contexts.get(target.identity());
        if (winner != null) {
          winner.borrowers++;
          selected = winner;
          redundant = created;
          created = null;
        } else {
          created.borrowers = 1;
          contexts.put(target.identity(), created);
          selected = created;
          created = null;
        }
      }
      var result = new Borrow(selected);
      try {
        if (redundant != null) redundant.close();
      } catch (Throwable failure) {
        closeOnFailure(result, failure);
        throw failure;
      }
      return result;
    } catch (IOException e) {
      var mapped =
          new BuildRetentionException(STORAGE_UNAVAILABLE, "Cannot open verified inventory", e);
      primaryFailure = mapped;
      throw mapped;
    } catch (RuntimeException | Error failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      synchronized (monitor) {
        if (reservationHeld) initializing--;
      }
      if (created != null) {
        if (primaryFailure == null) created.close();
        else closeOnFailure(created, primaryFailure);
      }
    }
  }

  public void validateInTransaction(Connection c, Opened opened, CancellationToken token) {
    if (!(opened instanceof Borrow b) || b.owner() != this || b.closed)
      throw fail(INVALID_REQUEST, "Foreign or closed inventory lease");
    validateContext(c, b.context, token);
  }

  /** Current consumer provenance is checked separately from the first immutable source binding. */
  public void validateConsumerInTransaction(
      Connection c,
      Opened opened,
      BuildLibraryReference current,
      String locale,
      String metadataScope,
      CancellationToken token) {
    validateInTransaction(c, opened, token);
    var lease = (Borrow) opened;
    if (!current.key().identity().equals(lease.context.binding.target.groupId())
        || !current.snapshotId().equals(lease.context.binding.target.snapshotId())
        || !locale.equals(lease.context.binding.locale)
        || !metadataScope.equals(lease.context.binding.metadataScope))
      throw fail(STALE_GENERATION, "Consumer differs from issued complete scope");
    try {
      new io.vibeminer.infrastructure.discoveryhistory.SqliteBuildLibraryRepository(catalog)
          .requireRegisteredReferenceInTransaction(c, current, token);
    } catch (SQLException failure) {
      throw storage(failure);
    }
  }

  public SourceAuthority openSource(Target target, Member member, CancellationToken token) {
    var opened = (Borrow) openSealed(target, token);
    try {
      var actual = opened.members(member.ordinal(), 1, token);
      if (actual.size() != 1 || !actual.getFirst().equals(member))
        throw fail(CORRUPT_STATE, "Member differs from sealed inventory");
      return new SourceAuthority(opened, member);
    } catch (Throwable e) {
      closeOnFailure(opened, e);
      throw e;
    }
  }

  public final class SourceAuthority implements AutoCloseable {
    private final Borrow opened;
    private final Member member;

    private SourceAuthority(Borrow opened, Member member) {
      this.opened = opened;
      this.member = member;
    }

    public Target target() {
      return opened.seal().target();
    }

    public ReservationPolicy reservationPolicy() {
      return policy;
    }

    public BuildLibraryReference reference() {
      return opened.context.facts.reference();
    }

    public BuildMetadataAdmissionFacts metadata() {
      return opened.context.facts;
    }

    public Member member() {
      return member;
    }

    public void validateInTransaction(Connection c, CancellationToken token) {
      SqliteBuildRetentionInventory.this.validateInTransaction(c, opened, token);
      try (var q =
          c.prepareStatement(
              "SELECT ckey,ekey,encoded_bytes FROM build_inventory_members WHERE generation=? AND"
                  + " ordinal=?")) {
        q.setString(1, opened.context.binding.generation);
        q.setLong(2, member.ordinal());
        try (var r = q.executeQuery()) {
          if (!r.next()
              || !r.getString(1).equals(member.contentKey())
              || !r.getString(2).equals(member.encodingKey())
              || integer(r, 3) != member.encodedBytes())
            throw fail(CORRUPT_STATE, "Exact source membership changed");
        }
      } catch (SQLException e) {
        throw storage(e);
      }
    }

    @Override
    public void close() {
      opened.close();
    }
  }

  private final class Borrow implements Opened {
    private final Context context;
    private boolean closed;

    Borrow(Context context) {
      this.context = context;
    }

    SqliteBuildRetentionInventory owner() {
      return SqliteBuildRetentionInventory.this;
    }

    public Seal seal() {
      if (closed) throw fail(NOT_READY, "Inventory lease closed");
      return new Seal(context.binding.target, context.binding.count, SqliteBuildInventory.REVISION);
    }

    public List<Member> members(long first, int maximum, CancellationToken token) {
      if (closed || first < 0 || first > context.binding.count || maximum < 1 || maximum > 128)
        throw fail(INVALID_REQUEST, "Invalid bounded inventory page");
      token.throwIfCancellationRequested();
      // Read-only connection: callers may already hold the ledger's IMMEDIATE write transaction.
      try (var c = catalog.connect();
          var q =
              c.prepareStatement(
                  "SELECT"
                      + " m.ordinal,m.ckey,m.ekey,m.encoded_bytes,c.decoded_bytes,e.encoded_bytes,EXISTS(SELECT"
                      + " 1 FROM en_lookup_candidates p WHERE p.artifact=c.artifact AND"
                      + " p.generation=c.generation AND p.ckey=m.ckey AND"
                      + " p.ekey=m.ekey),EXISTS(SELECT 1 FROM build_inventory_references r WHERE"
                      + " r.generation=m.generation AND r.ckey=m.ckey AND r.ekey=m.ekey AND"
                      + " r.resolution='RESOLVED' AND r.encoded_bytes=m.encoded_bytes AND"
                      + " r.decoded_bytes=m.decoded_bytes),m.decoded_bytes FROM"
                      + " build_inventory_members m LEFT JOIN en_lookup_content c ON c.artifact=?"
                      + " AND c.generation=? AND c.ckey=m.ckey LEFT JOIN en_lookup_encoding e ON"
                      + " e.artifact=c.artifact AND e.generation=c.generation AND e.ekey=m.ekey"
                      + " WHERE m.generation=? AND m.ordinal>=? ORDER BY m.ordinal LIMIT ?")) {
        q.setString(1, context.en.artifactId());
        q.setString(2, context.en.generation());
        q.setString(3, context.binding.generation);
        q.setLong(4, first);
        q.setInt(5, maximum);
        var result = new ArrayList<Member>();
        try (var r = q.executeQuery()) {
          while (r.next()) {
            token.throwIfCancellationRequested();
            long ordinal = integer(r, 1), bytes = integer(r, 4);
            if (integer(r, 5) != integer(r, 9)
                || integer(r, 5) < 0
                || integer(r, 6) != bytes
                || integer(r, 7) != 1
                || integer(r, 8) != 1)
              throw fail(
                  CORRUPT_STATE, "Member differs from authenticated EN/manifest expectations");
            if (ordinal != first + result.size())
              throw fail(CORRUPT_STATE, "Noncontiguous sealed membership");
            String ck = r.getString(2), ek = r.getString(3);
            String commitment =
                hash(
                    "retention-member-v1",
                    context.binding.target.scopeCommitment(),
                    Long.toString(ordinal),
                    ck,
                    ek,
                    Long.toString(bytes));
            result.add(
                new Member(
                    ordinal,
                    hash(
                        "retention-member-id-v1",
                        context.binding.target.inventoryId(),
                        Long.toString(ordinal)),
                    ck,
                    ek,
                    bytes,
                    policy.cost(bytes),
                    commitment));
          }
        }
        if (result.size() != Math.min(maximum, context.binding.count - first))
          throw fail(CORRUPT_STATE, "Sealed membership incomplete");
        return List.copyOf(result);
      } catch (SQLException e) {
        throw storage(e);
      }
    }

    @Override
    public void close() {
      Context dispose = null;
      synchronized (monitor) {
        if (closed) return;
        closed = true;
        context.borrowers--;
        if (SqliteBuildRetentionInventory.this.closed && context.borrowers == 0) {
          contexts.remove(context.binding.target.identity());
          dispose = context;
        }
      }
      if (dispose != null) dispose.close();
    }
  }

  private final class HeldSources implements AutoCloseable {
    final List<ObjectReadLease> leases = new ArrayList<>();

    HeldSources(BuildMetadataAdmissionFacts facts, CancellationToken token) throws IOException {
      try {
        for (var f : facts.configurations()) {
          var lease = store.openRead(f.originalAlias().object().digest(), token);
          leases.add(lease);
          if (!lease.object().equals(f.originalAlias().object()))
            throw fail(CORRUPT_STATE, "Configuration object changed");
        }
        for (var f : facts.metadata()) {
          if (f.role() == Role.ENCODING) continue;
          var lease = store.openRead(f.proof().object().digest(), token);
          leases.add(lease);
          if (!lease.object().equals(f.proof().object()))
            throw fail(CORRUPT_STATE, "Metadata object changed");
        }
      } catch (Throwable e) {
        closeOnFailure(this, e);
        throw e;
      }
    }

    public void close() {
      var pending = leases.toArray(AutoCloseable[]::new);
      leases.clear();
      closeAll(pending);
    }
  }

  private final class Context implements AutoCloseable {
    final Binding binding;
    final BuildMetadataAdmissionFacts facts;
    final SqliteEncodingLookupIndex.Session en;
    final HeldSources held;
    int borrowers;

    Context(
        Binding b,
        BuildMetadataAdmissionFacts f,
        SqliteEncodingLookupIndex.Session en,
        HeldSources held) {
      binding = b;
      facts = f;
      this.en = en;
      this.held = held;
    }

    public void close() {
      closeAll(held, en);
    }
  }

  private void validateContext(Connection c, Context context, CancellationToken token)
      throws RuntimeException {
    try {
      var b = read(c, context.binding.target.inventoryId());
      if (!b.equals(context.binding)) throw fail(CORRUPT_STATE, "Issuance changed");
      String scope =
          hash(
              "retention-scope-v1",
              b.generation,
              BuildMetadataAdmissionRepository.closureId(context.facts),
              context.en.artifactId(),
              context.en.generation(),
              SqliteBuildInventory.REVISION,
              b.locale,
              b.metadataScope,
              policy.commitment());
      var ref = context.facts.reference();
      String id = hash("retention-issuance-v1", ref.key().identity(), ref.snapshotId(), scope);
      if (!scope.equals(b.target.scopeCommitment())
          || !id.equals(b.target.inventoryId())
          || !b.target
              .admissionId()
              .equals(BuildMetadataAdmissionRepository.closureId(context.facts)))
        throw fail(CORRUPT_STATE, "Issuance proof commitment changed");
      validate(c, context.facts, context.en, b.generation, b.count, token);
    } catch (SQLException e) {
      throw storage(e);
    }
  }

  private void validate(
      Connection c,
      BuildMetadataAdmissionFacts facts,
      SqliteEncodingLookupIndex.Session en,
      String generation,
      long count,
      CancellationToken token)
      throws SQLException {
    if (c.getAutoCommit())
      throw new IllegalStateException("Authority validation requires caller transaction");
    token.throwIfCancellationRequested();
    admissions.validateInTransaction(c, facts, token);
    en.validateInTransaction(c, token);
    var sources =
        facts.metadata().stream()
            .filter(f -> f.role() != Role.ENCODING)
            .map(
                f ->
                    new InventoryStaging.Source(
                        f.role().name(), CanonicalContentRecords.encode(f.proof())))
            .toList();
    InventoryStaging.requireContext(
        c,
        generation,
        facts.reference().snapshotId(),
        BuildMetadataAdmissionRepository.closureId(facts),
        en.artifactId(),
        en.generation(),
        SqliteBuildInventory.REVISION,
        sources);
    try (var q =
        c.prepareStatement(
            "SELECT status,member_count FROM build_inventory_generations WHERE id=?")) {
      q.setString(1, generation);
      try (var r = q.executeQuery()) {
        if (!r.next() || !r.getString(1).equals("READY") || integer(r, 2) != count)
          throw fail(NOT_READY, "Complete READY inventory required");
      }
    }
  }

  private Binding read(Connection c, String id) throws SQLException {
    try (var q =
        c.prepareStatement("SELECT * FROM build_retention_bindings WHERE inventory_id=?")) {
      q.setString(1, id);
      try (var r = q.executeQuery()) {
        if (!r.next()) throw fail(NOT_FOUND, "Inventory target has not been issued");
        var t =
            new Target(
                r.getString("group_id"),
                r.getString("snapshot_id"),
                r.getString("admission_id"),
                id,
                integer(r, "issuance"),
                r.getString("scope_revision"),
                r.getString("scope_commitment"));
        return new Binding(
            t,
            r.getString("generation"),
            UUID.fromString(r.getString("event_id")),
            integer(r, "observation_sequence"),
            Math.toIntExact(integer(r, "row_ordinal")),
            r.getString("locale"),
            r.getString("metadata_scope"),
            r.getString("policy"),
            integer(r, "member_count"));
      }
    }
  }

  private interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  private <T> T transaction(Work<T> work) {
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try {
        var result = work.run(c);
        c.commit();
        return result;
      } catch (Throwable e) {
        try {
          c.rollback();
        } catch (SQLException x) {
          e.addSuppressed(x);
        }
        throw e;
      }
    } catch (SQLException e) {
      throw storage(e);
    }
  }

  private static long integer(ResultSet r, String field) throws SQLException {
    Object o = r.getObject(field);
    if (!(o instanceof Integer || o instanceof Long))
      throw fail(CORRUPT_STATE, "Noninteger inventory identity");
    return ((Number) o).longValue();
  }

  private static long integer(ResultSet r, int field) throws SQLException {
    Object o = r.getObject(field);
    if (!(o instanceof Integer || o instanceof Long))
      throw fail(CORRUPT_STATE, "Noninteger inventory identity");
    return ((Number) o).longValue();
  }

  private static BuildRetentionException fail(BuildRetentionException.Code code, String message) {
    return new BuildRetentionException(code, message);
  }

  private static BuildRetentionException storage(SQLException e) {
    return new BuildRetentionException(
        STORAGE_UNAVAILABLE, "Cannot read sealed inventory authority", e);
  }

  private static void closeOnFailure(AutoCloseable resource, Throwable primary) {
    try {
      resource.close();
    } catch (Throwable cleanup) {
      if (cleanup != primary) primary.addSuppressed(cleanup);
    }
  }

  private static void closeAll(AutoCloseable... resources) {
    Throwable primary = null;
    for (var resource : resources)
      try {
        resource.close();
      } catch (Throwable failure) {
        if (primary == null) primary = failure;
        else if (primary != failure) primary.addSuppressed(failure);
      }
    if (primary instanceof RuntimeException runtime) throw runtime;
    if (primary instanceof Error error) throw error;
    if (primary != null)
      throw new BuildRetentionException(
          STORAGE_UNAVAILABLE, "Cannot retire inventory leases", primary);
  }

  private static String hash(String... parts) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (String p : parts) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(p))
          throw new IllegalArgumentException("Strict Unicode required");
        var raw = p.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void close() {
    var dispose = new ArrayList<Context>();
    synchronized (monitor) {
      if (closed) return;
      closed = true;
      for (var it = contexts.values().iterator(); it.hasNext(); ) {
        var c = it.next();
        if (c.borrowers == 0) {
          dispose.add(c);
          it.remove();
        }
      }
    }
    closeAll(dispose.toArray(AutoCloseable[]::new));
  }
}
