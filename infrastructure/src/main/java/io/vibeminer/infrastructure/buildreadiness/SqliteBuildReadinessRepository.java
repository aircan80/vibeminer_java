package io.vibeminer.infrastructure.buildreadiness;

import static io.vibeminer.application.buildreadiness.BuildReadinessException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildoverview.BuildOverview;
import io.vibeminer.application.buildreadiness.*;
import io.vibeminer.application.buildretention.BuildRetention;
import io.vibeminer.application.catalog.CatalogException;
import io.vibeminer.infrastructure.discoveryhistory.*;
import io.vibeminer.infrastructure.inventory.SqliteBuildRetentionInventory;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import org.sqlite.*;

/** SQL-only dated overlay. Read never opens CAS, source bodies, producers, or a migration. */
public final class SqliteBuildReadinessRepository implements BuildReadinessBackend {
  public record Limits(long maximumRows, long maximumSqlInstructions, Duration maximumDuration) {
    public Limits {
      if (maximumRows < 1
          || maximumRows > 1_000_000
          || maximumSqlInstructions < 1000
          || maximumDuration == null
          || maximumDuration.isNegative()
          || maximumDuration.isZero()
          || maximumDuration.compareTo(Duration.ofMinutes(1)) > 0)
        throw new IllegalArgumentException("Bounded summary work required");
    }

    public static Limits defaults() {
      return new Limits(100_000, 1_000_000, Duration.ofSeconds(10));
    }
  }

  private static final long MAXIMUM_METADATA_BYTES = 64L << 20;
  private final Path database;
  private final SqliteCatalog catalog;
  private final String policy;
  private final Limits limits;

  public SqliteBuildReadinessRepository(
      Path database, SqliteBuildRetentionInventory.ReservationPolicy policy) {
    this(database, policy, Limits.defaults());
  }

  public SqliteBuildReadinessRepository(
      Path database, SqliteBuildRetentionInventory.ReservationPolicy policy, Limits limits) {
    this.database = Objects.requireNonNull(database).toAbsolutePath().normalize();
    this.catalog = new SqliteCatalog(this.database);
    this.policy = Objects.requireNonNull(policy).commitment();
    this.limits = Objects.requireNonNull(limits);
  }

  @Override
  public List<BuildReadiness.Status> read(
      List<BuildReadiness.Context> contexts, CancellationToken token) {
    Objects.requireNonNull(token);
    contexts = checked(contexts);
    var work = new Work(token, limits);
    work.check();
    try {
      try {
        if (!Files.readAttributes(database, BasicFileAttributes.class).isRegularFile())
          throw new IOException("Catalog is not a regular file");
      } catch (NoSuchFileException absent) {
        work.check();
        return contexts.stream()
            .map(
                c ->
                    new BuildReadiness.Status(
                        c, BuildReadiness.AdmissionPresence.ABSENT, Optional.empty()))
            .toList();
      }
      var config = new SQLiteConfig();
      config.setReadOnly(true);
      try (var c = config.createConnection("jdbc:sqlite:" + database)) {
        try (var q = c.createStatement()) {
          q.execute("PRAGMA query_only=ON");
          q.execute("PRAGMA busy_timeout=1000");
        }
        c.setAutoCommit(false);
        try (var ignored = work.install(c)) {
          boolean registered = table(c, "build_library_registrations");
          boolean admitted = table(c, "build_open_admissions");
          boolean observations = table(c, "build_readiness_observations");
          var result = new ArrayList<BuildReadiness.Status>();
          for (var context : contexts) {
            work.check();
            if (registered) requireReference(c, context.reference(), work);
            var current = admitted ? admission(c, context) : Optional.<String>empty();
            Optional<BuildReadiness.Observation> historical = Optional.empty();
            if (observations) {
              var stored = stored(c, BuildReadinessRecords.context(context, policy));
              if (stored.isPresent()) {
                var observation = stored.get().observation();
                new BuildReadiness.Status(context, presence(current), Optional.of(observation));
                requireOrigin(c, observation.origin(), work);
                if (stored.get().admission().equals(current)
                    && (observation.target().isEmpty()
                        || binding(c, context, observation.target().get())))
                  historical = Optional.of(observation);
              }
            }
            result.add(new BuildReadiness.Status(context, presence(current), historical));
          }
          work.check();
          c.rollback();
          return List.copyOf(result);
        }
      }
    } catch (IOException | SQLException failure) {
      work.check(failure);
      throw fail(STORAGE, "Cannot read dated readiness overlay", failure);
    } catch (BuildReadinessException failure) {
      throw failure;
    } catch (IllegalArgumentException | java.time.DateTimeException failure) {
      throw fail(CORRUPT_RESULT, "Stored readiness metadata is inconsistent", failure);
    }
  }

  @Override
  public void record(CompletedAssessment assessment, CancellationToken token) {
    Objects.requireNonNull(assessment);
    Objects.requireNonNull(token);
    var work = new Work(token, limits);
    work.check();
    var context = assessment.context();
    var observation = assessment.observation();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try (var ignored = work.install(c)) {
        try {
          requireReference(c, context.reference(), work);
          requireOrigin(c, observation.origin(), work);
          var admission = admission(c, context);
          new BuildReadiness.Status(context, presence(admission), Optional.of(observation));
          if (assessment.metadata() == BuildOverview.MetadataState.READY && admission.isEmpty())
            throw fail(CORRUPT_RESULT, "Completed ready metadata lacks its admitted context", null);
          if (observation.target().isPresent()
              && (!binding(c, context, observation.target().get())
                  || !admission.equals(Optional.of(observation.target().get().admissionId()))))
            throw fail(
                CORRUPT_RESULT, "Assessed target differs from current recorded context", null);
          var key = BuildReadinessRecords.context(context, policy);
          // Validate any existing row; a new assessment does not silently bless corrupt cache
          // bytes.
          var previous = stored(c, key);
          if (previous.isEmpty()) {
            try (var q = c.createStatement();
                var rows = q.executeQuery("SELECT count(*) FROM build_readiness_observations")) {
              if (!rows.next() || integer(rows, 1, 0, Long.MAX_VALUE) >= limits.maximumRows())
                throw fail(LIMIT, "Dated readiness summary quota reached", null);
            }
          }
          byte[] bytes = BuildReadinessRecords.encode(key, admission, observation);
          try (var q =
              c.prepareStatement(
                  "INSERT INTO build_readiness_observations(context_id,record,record_sha256)"
                      + " VALUES(?,?,?) ON CONFLICT(context_id) DO UPDATE SET"
                      + " record=excluded.record,record_sha256=excluded.record_sha256")) {
            q.setString(1, key);
            q.setBytes(2, bytes);
            q.setString(3, BuildReadinessRecords.hash(bytes));
            q.executeUpdate();
          }
          // Latest successful recording commit wins even if its honest measurement clock moved
          // back.
          work.check();
          c.commit();
        } catch (SQLException | RuntimeException | Error failure) {
          try {
            c.rollback();
          } catch (SQLException cleanup) {
            failure.addSuppressed(cleanup);
          }
          throw failure;
        }
      }
    } catch (SQLException failure) {
      work.check(failure);
      throw fail(STORAGE, "Cannot record completed dated readiness", failure);
    } catch (BuildReadinessException failure) {
      throw failure;
    } catch (CatalogException failure) {
      throw fail(
          failure.code() == CatalogException.Code.STORAGE_UNAVAILABLE ? STORAGE : CORRUPT_RESULT,
          "Readiness catalog is unavailable or inconsistent",
          failure);
    } catch (IllegalArgumentException | java.time.DateTimeException failure) {
      throw fail(CORRUPT_RESULT, "Recorded readiness metadata is inconsistent", failure);
    }
  }

  private static List<BuildReadiness.Context> checked(List<BuildReadiness.Context> contexts) {
    try {
      if (contexts == null || contexts.size() > BuildReadiness.MAXIMUM_CONTEXTS)
        throw new IllegalArgumentException("At most 100 exact contexts required");
      var values = List.copyOf(contexts);
      var keys = new HashSet<List<String>>();
      for (var context : values)
        if (!keys.add(context.key())) throw new IllegalArgumentException("Duplicate context");
      return values;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw fail(INVALID_REQUEST, "Bounded distinct readiness contexts required", invalid);
    }
  }

  private static BuildReadiness.AdmissionPresence presence(Optional<String> current) {
    return current.isPresent()
        ? BuildReadiness.AdmissionPresence.PRESENT
        : BuildReadiness.AdmissionPresence.ABSENT;
  }

  private static Optional<String> admission(Connection c, BuildReadiness.Context context)
      throws SQLException {
    // A legacy snapshot tuple alone must not transfer admission across KeyRing/unknown-ref groups.
    try (var q =
        c.prepareStatement(
            "SELECT a.closure_sha256,a.required_count,(SELECT count(*) FROM build_open_role_facts f"
                + " WHERE f.context_id=a.context_id) FROM build_open_admissions a WHERE"
                + " a.snapshot_id=? AND a.locale=? AND a.scope_revision=? AND EXISTS(SELECT 1 FROM"
                + " build_open_receipts r WHERE r.context_id=a.context_id AND r.group_id=?)")) {
      q.setString(1, context.reference().snapshotId());
      q.setString(2, context.locale());
      q.setString(3, context.metadataScopeRevision());
      q.setString(4, context.reference().key().identity());
      try (var rows = q.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        var digest = rows.getString(1);
        long count = integer(rows, 2, 5, 1024);
        if (digest == null
            || !digest.matches("[0-9a-f]{64}")
            || integer(rows, 3, 0, 1024) != count
            || rows.next())
          throw new IllegalArgumentException("Invalid or ambiguous admission header");
        return Optional.of(digest);
      }
    }
  }

  private static Optional<BuildReadinessRecords.Stored> stored(Connection c, String context)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT length(record),record,record_sha256,typeof(record) FROM"
                + " build_readiness_observations WHERE context_id=?")) {
      q.setString(1, context);
      try (var rows = q.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        blobLength(rows, 1, 4, BuildReadinessRecords.MAX_BYTES);
        var bytes = rows.getBytes(2);
        if (!BuildReadinessRecords.hash(bytes).equals(rows.getString(3)))
          throw new IllegalArgumentException("Summary checksum differs");
        return Optional.of(BuildReadinessRecords.decode(context, bytes));
      }
    }
  }

  private boolean binding(
      Connection c, BuildReadiness.Context context, BuildRetention.Target target)
      throws SQLException {
    if (!table(c, "build_retention_bindings")) return false;
    try (var q =
        c.prepareStatement(
            "SELECT"
                + " issuance,group_id,snapshot_id,admission_id,scope_revision,scope_commitment,locale,metadata_scope,policy,observation_sequence,row_ordinal,member_count"
                + " FROM build_retention_bindings WHERE inventory_id=?")) {
      q.setString(1, target.inventoryId());
      try (var row = q.executeQuery()) {
        if (!row.next()) return false;
        long issuance = integer(row, 1, 1, Long.MAX_VALUE);
        integer(row, 10, 1, Long.MAX_VALUE);
        integer(row, 11, 0, 9999);
        integer(row, 12, 0, Long.MAX_VALUE);
        return issuance == target.inventoryGeneration()
            && target.groupId().equals(row.getString(2))
            && target.snapshotId().equals(row.getString(3))
            && target.admissionId().equals(row.getString(4))
            && target.scopeRevision().equals(row.getString(5))
            && target.scopeCommitment().equals(row.getString(6))
            && context.locale().equals(row.getString(7))
            && context.metadataScopeRevision().equals(row.getString(8))
            && policy.equals(row.getString(9));
      }
    }
  }

  private static void requireReference(Connection c, BuildLibraryReference reference, Work work)
      throws SQLException {
    var refs = eventReferences(c, reference.eventId(), work);
    if (refs.stream().noneMatch(reference::equals))
      throw new IllegalArgumentException("Exact observation reference changed");
    requireGroup(c, reference, refs, work);
  }

  private static void requireOrigin(Connection c, BuildReadiness.Origin origin, Work work)
      throws SQLException {
    var refs = eventReferences(c, origin.eventId(), work);
    var reference =
        refs.stream()
            .filter(
                r ->
                    r.observationSequence() == origin.observationSequence()
                        && r.versionsRowOrdinal() == origin.versionsRowOrdinal()
                        && r.key().identity().equals(origin.groupId())
                        && r.snapshotId().equals(origin.snapshotId())
                        && r.region().equals(origin.region()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Original assessment observation changed"));
    requireGroup(c, reference, refs, work);
  }

  private static void requireGroup(
      Connection c, BuildLibraryReference reference, List<BuildLibraryReference> refs, Work work)
      throws SQLException {
    int first =
        refs.stream()
            .filter(r -> r.key().identity().equals(reference.key().identity()))
            .mapToInt(BuildLibraryReference::versionsRowOrdinal)
            .min()
            .orElseThrow();
    try (var q =
        c.prepareStatement(
            "SELECT"
                + " e.first_row_ordinal,length(g.key_record),g.key_record,g.key_sha256,typeof(g.key_record)"
                + " FROM build_library_event_groups e JOIN build_library_registrations r ON"
                + " r.event_id=e.event_id JOIN build_library_groups g ON g.group_id=e.group_id"
                + " WHERE e.event_id=? AND e.group_id=?")) {
      q.setString(1, reference.eventId().toString());
      q.setString(2, reference.key().identity());
      try (var row = q.executeQuery()) {
        if (!row.next() || integer(row, 1, 0, 9999) != first)
          throw new IllegalArgumentException("Registered group reference changed");
        work.bytes(blobLength(row, 2, 5, BuildLibraryRecords.MAX_KEY_BYTES));
        var bytes = row.getBytes(3);
        if (!BuildReadinessRecords.hash(bytes).equals(row.getString(4))
            || !BuildLibraryRecords.key(bytes).equals(reference.key()))
          throw new IllegalArgumentException("Registered group identity changed");
      }
    }
  }

  private static List<BuildLibraryReference> eventReferences(Connection c, UUID id, Work work)
      throws SQLException {
    var cached = work.events.get(id);
    if (cached != null) return cached.references();
    try (var q =
        c.prepareStatement(
            "SELECT"
                + " event_sequence,product_id,region,completed_at,parser_revision,build_count,length(record),record,record_sha256,typeof(record)"
                + " FROM discovery_history_events WHERE event_id=?")) {
      q.setString(1, id.toString());
      try (var row = q.executeQuery()) {
        if (!row.next()) throw new IllegalArgumentException("Missing historical observation");
        long size = blobLength(row, 7, 10, DiscoveryHistoryRecords.MAX_RECORD_BYTES);
        long sequence = integer(row, 1, 1, Long.MAX_VALUE);
        work.bytes(size);
        var record = row.getBytes(8);
        if (!BuildReadinessRecords.hash(record).equals(row.getString(9)))
          throw new IllegalArgumentException("Observation metadata checksum differs");
        var summary = DiscoveryHistoryRecords.summary(record, sequence);
        var refs = DiscoveryHistoryRecords.libraryReferences(record, sequence);
        if (!summary.eventId().equals(id)
            || !summary.productId().equals(row.getString(2))
            || !summary.region().equals(row.getString(3))
            || !summary.completedAt().toString().equals(row.getString(4))
            || !summary.parserRevision().equals(row.getString(5))
            || refs.size() != integer(row, 6, 1, 64))
          throw new IllegalArgumentException("Observation header differs from record");
        try (var builds =
            c.prepareStatement(
                "SELECT position,snapshot_id,versions_row_ordinal,channel FROM"
                    + " discovery_history_builds WHERE event_id=? ORDER BY position")) {
          builds.setString(1, id.toString());
          try (var rows = builds.executeQuery()) {
            int n = 0;
            while (rows.next()) {
              work.check();
              if (n >= refs.size()
                  || integer(rows, 1, 0, 63) != n
                  || !rows.getString(2).equals(refs.get(n).snapshotId())
                  || integer(rows, 3, 0, 9999) != refs.get(n).versionsRowOrdinal()
                  || !rows.getString(4).equals(refs.get(n).key().channel()))
                throw new IllegalArgumentException("Observation row membership changed");
              n++;
            }
            if (n != refs.size())
              throw new IllegalArgumentException("Incomplete observation membership");
          }
        }
        work.cache(id, new Event(refs, size));
        return refs;
      }
    }
  }

  /** SQLite REAL/TEXT values must never round or wrap into integer provenance. */
  private static long integer(ResultSet row, int column, long minimum, long maximum)
      throws SQLException {
    Object value = row.getObject(column);
    if (!(value instanceof Integer || value instanceof Long))
      throw new IllegalArgumentException("Persisted numeric identity is not a SQLite integer");
    long number = ((Number) value).longValue();
    if (number < minimum || number > maximum)
      throw new IllegalArgumentException("Persisted numeric identity exceeds its exact bounds");
    return number;
  }

  private static long blobLength(ResultSet row, int lengthColumn, int typeColumn, long maximum)
      throws SQLException {
    if (!"blob".equals(row.getString(typeColumn)))
      throw new IllegalArgumentException("Persisted record is not a BLOB");
    return integer(row, lengthColumn, 1, maximum);
  }

  private record Event(List<BuildLibraryReference> references, long bytes) {}

  private static boolean table(Connection c, String name) throws SQLException {
    try (var q = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
      q.setString(1, name);
      try (var row = q.executeQuery()) {
        return row.next();
      }
    }
  }

  private static BuildReadinessException fail(
      BuildReadinessException.Code code, String message, Throwable cause) {
    return new BuildReadinessException(code, message, cause);
  }

  private static final class Work {
    final CancellationToken token;
    final Limits limits;
    final long started = System.nanoTime();
    final LinkedHashMap<UUID, Event> events = new LinkedHashMap<>(16, .75f, true);
    long instructions, metadataBytes, cachedBytes;
    boolean exceeded;

    Work(CancellationToken token, Limits limits) {
      this.token = token;
      this.limits = limits;
    }

    void check() {
      check(null);
    }

    void check(Throwable cause) {
      token.throwIfCancellationRequested();
      if (exceeded || System.nanoTime() - started >= limits.maximumDuration().toNanos())
        throw fail(LIMIT, "Dated readiness SQL work limit reached", cause);
    }

    void bytes(long count) {
      check();
      if (count > MAXIMUM_METADATA_BYTES - metadataBytes)
        throw fail(LIMIT, "Dated readiness metadata budget reached", null);
      metadataBytes += count;
    }

    void cache(UUID id, Event event) {
      while (!events.isEmpty()
          && (events.size() >= 16 || cachedBytes + event.bytes() > (8L << 20))) {
        var first = events.entrySet().iterator();
        cachedBytes -= first.next().getValue().bytes();
        first.remove();
      }
      events.put(id, event);
      cachedBytes += event.bytes();
    }

    AutoCloseableSql install(Connection c) throws SQLException {
      ProgressHandler.setHandler(
          c,
          100,
          new ProgressHandler() {
            protected int progress() {
              instructions += 100;
              if (token.isCancellationRequested()
                  || instructions > limits.maximumSqlInstructions()
                  || System.nanoTime() - started >= limits.maximumDuration().toNanos()) {
                exceeded = true;
                return 1;
              }
              return 0;
            }
          });
      return () -> ProgressHandler.clearHandler(c);
    }
  }

  @FunctionalInterface
  private interface AutoCloseableSql extends AutoCloseable {
    @Override
    void close() throws SQLException;
  }
}
