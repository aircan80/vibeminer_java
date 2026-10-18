package io.vibeminer.infrastructure.discoveryhistory;

import static io.vibeminer.application.buildlibrary.BuildLibraryException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.sqlite.ProgressHandler;

/** Bounded historical library projection. It never reads source bodies or claims readiness. */
public final class SqliteBuildLibraryReader implements BuildLibraryReader {
  public static final long MAXIMUM_PAGE_METADATA_BYTES = 64L << 20;
  public static final long MAXIMUM_CACHE_METADATA_BYTES = 8L << 20;
  private final SqliteBuildLibraryRepository library;
  private final SqliteDiscoveryHistoryRepository history;
  private final long pageBytes;
  private final long maximumSqlInstructions;
  private final LongSupplier nanoClock;

  public SqliteBuildLibraryReader(SqliteCatalog catalog) {
    this(catalog, MAXIMUM_PAGE_METADATA_BYTES);
  }

  public SqliteBuildLibraryReader(SqliteCatalog catalog, long maximumPageMetadataBytes) {
    this(catalog, maximumPageMetadataBytes, 20_000_000L, System::nanoTime);
  }

  SqliteBuildLibraryReader(
      SqliteCatalog catalog,
      long maximumPageMetadataBytes,
      long maximumSqlInstructions,
      LongSupplier nanoClock) {
    Objects.requireNonNull(catalog);
    this.nanoClock = Objects.requireNonNull(nanoClock);
    if (maximumSqlInstructions < 1 || maximumSqlInstructions > 20_000_000L)
      throw new IllegalArgumentException("Bounded SQL work required");
    this.maximumSqlInstructions = maximumSqlInstructions;
    if (maximumPageMetadataBytes < 1 || maximumPageMetadataBytes > MAXIMUM_PAGE_METADATA_BYTES)
      throw new IllegalArgumentException("Metadata projection budget1..64MiB required");
    library = new SqliteBuildLibraryRepository(catalog);
    history = new SqliteDiscoveryHistoryRepository(catalog);
    pageBytes = maximumPageMetadataBytes;
  }

  @Override
  public BuildLibraryPage page(
      BuildLibraryQuery query,
      Optional<BuildLibraryCursor> cursor,
      int size,
      CancellationToken token) {
    if (query == null || cursor == null || token == null || size < 1 || size > 100)
      throw fail(INVALID_REQUEST, "Exact query and page size1..100 required");
    return readTx(
        token,
        c -> {
          long revision = revision(c);
          try {
            cursor.ifPresent(value -> value.requireContext(query, size, revision));
          } catch (IllegalArgumentException stale) {
            throw fail(STALE_CURSOR, "Library changed; refresh this query");
          }
          var states = states(c, query.productId());
          String sql =
              "SELECT g.group_id,r.registration_sequence,length(g.key_record) FROM"
                  + " build_library_groups g JOIN build_library_registrations r ON"
                  + " r.event_id=g.first_event_id WHERE g.product_id=?";
          if (cursor.isPresent())
            sql +=
                " AND (r.registration_sequence<? OR (r.registration_sequence=? AND g.group_id>?))";
          sql +=
              switch (query.filter()) {
                case ALL -> "";
                case LOCAL -> " AND " + localPredicate("g.group_id");
                case CURRENTLY_ADVERTISED ->
                    " AND EXISTS(SELECT 1 FROM build_library_region_states s JOIN"
                        + " build_library_event_groups e ON e.event_id=s.latest_successful_event_id"
                        + " WHERE s.product_id=g.product_id AND s.check_state='SUCCESS' AND"
                        + " e.group_id=g.group_id"
                        + (query.region().isPresent() ? " AND s.region=?" : "")
                        + ")";
              };
          if (query.region().isPresent())
            sql +=
                " AND EXISTS(SELECT 1 FROM build_library_event_groups e JOIN"
                    + " discovery_history_events h ON h.event_id=e.event_id"
                    + " WHERE e.group_id=g.group_id AND h.region=?)";
          sql += " ORDER BY r.registration_sequence DESC,g.group_id ASC";
          if (query.search().isEmpty()) sql += " LIMIT ?";
          var candidates = new ArrayList<Candidate>();
          var budget = new Budget();
          try (var q = c.prepareStatement(sql)) {
            int i = 1;
            q.setString(i++, query.productId());
            if (cursor.isPresent()) {
              q.setLong(i++, cursor.get().beforeFirstRegistrationSequence());
              q.setLong(i++, cursor.get().beforeFirstRegistrationSequence());
              q.setString(i++, cursor.get().beforeGroupId());
            }
            if (query.filter() == BuildLibraryProjection.Filter.CURRENTLY_ADVERTISED
                && query.region().isPresent()) q.setString(i++, query.region().orElseThrow());
            if (query.region().isPresent()) q.setString(i++, query.region().orElseThrow());
            if (query.search().isEmpty()) q.setInt(i, size + 1);
            try (var rows = q.executeQuery()) {
              while (rows.next()) {
                token.throwIfCancellationRequested();
                var candidate = new Candidate(rows.getString(1), rows.getLong(2));
                if (query.search().isPresent()) {
                  long bytes = rows.getLong(3);
                  if (bytes < 1 || bytes > BuildLibraryRecords.MAX_KEY_BYTES)
                    throw fail(CORRUPT_LIBRARY, "Search group key size is invalid");
                  budget.chargeKey(bytes);
                  var group =
                      library
                          .group(c, candidate.id())
                          .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Search group disappeared"));
                  if (!query.matchesKey(group.key())) continue;
                }
                candidates.add(candidate);
                if (candidates.size() == size + 1) break;
              }
            }
          }
          var cache = new Cache();
          var entries = new ArrayList<BuildLibraryPage.Entry>();
          for (var candidate : candidates) {
            token.throwIfCancellationRequested();
            var entry = entry(c, candidate, states, budget, cache, token);
            if (!query.matches(entry.row()))
              throw fail(CORRUPT_LIBRARY, "Indexed filter differs from checked library facts");
            entries.add(entry);
          }
          boolean more = entries.size() > size;
          var visible = List.copyOf(more ? entries.subList(0, size) : entries);
          Optional<BuildLibraryCursor> next =
              more
                  ? Optional.of(
                      new BuildLibraryCursor(
                          query.identity(),
                          revision,
                          visible.getLast().firstRegistrationSequence(),
                          visible.getLast().row().group().key().identity(),
                          size))
                  : Optional.empty();
          return new BuildLibraryPage(query, size, revision, visible, next);
        });
  }

  @Override
  public BuildLibraryPage.Entry select(String groupId, CancellationToken token) {
    if (groupId == null || !groupId.matches("[0-9a-f]{64}") || token == null)
      throw fail(INVALID_REQUEST, "Exact group identity required");
    return readTx(
        token,
        c -> {
          var group =
              library
                  .group(c, groupId)
                  .orElseThrow(() -> fail(NOT_FOUND, "Library group was not found"));
          long first;
          try (var q =
              c.prepareStatement(
                  "SELECT registration_sequence FROM build_library_registrations WHERE"
                      + " event_id=?")) {
            q.setString(1, group.seen().firstEventId().toString());
            try (var row = q.executeQuery()) {
              if (!row.next()) throw fail(CORRUPT_LIBRARY, "Group registration is missing");
              first = row.getLong(1);
            }
          }
          return entry(
              c,
              new Candidate(groupId, first),
              states(c, group.key().productId()),
              new Budget(),
              new Cache(),
              token);
        });
  }

  private BuildLibraryPage.Entry entry(
      Connection c,
      Candidate candidate,
      List<BuildLibraryRegionalState> states,
      Budget budget,
      Cache cache,
      CancellationToken token)
      throws SQLException {
    var group =
        library
            .group(c, candidate.id())
            .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Selected library group is missing"));
    var references = new ArrayList<BuildLibraryReference>();
    var regions = new HashSet<String>();
    String sql =
        "SELECT h.event_id,h.region FROM build_library_event_groups e JOIN discovery_history_events"
            + " h ON h.event_id=e.event_id WHERE e.group_id=? AND h.event_sequence=(SELECT"
            + " max(h2.event_sequence) FROM build_library_event_groups e2 JOIN"
            + " discovery_history_events h2 ON h2.event_id=e2.event_id WHERE e2.group_id=e.group_id"
            + " AND h2.region=h.region) ORDER BY h.region LIMIT 65";
    try (var q = c.prepareStatement(sql)) {
      q.setString(1, candidate.id());
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          token.throwIfCancellationRequested();
          if (!regions.add(rows.getString(2))
              || regions.size() > BuildLibraryPage.MAX_REGIONS_PER_GROUP)
            throw fail(LIMIT_EXCEEDED, "Group regional projection limit exceeded");
          UUID eventId = UUID.fromString(rows.getString(1));
          var projected = cache.get(c, eventId, budget);
          int before = references.size();
          for (var reference : projected) {
            if (reference.key().identity().equals(candidate.id())) {
              if (!reference.key().equals(group.key())
                  || !reference.region().equals(rows.getString(2)))
                throw fail(
                    CORRUPT_LIBRARY,
                    "Projected group correspondence differs from stored exact key");
              references.add(reference);
            }
          }
          if (before == references.size())
            throw fail(CORRUPT_LIBRARY, "Registered group is absent from its event metadata");
        }
      }
    }
    if (references.isEmpty())
      throw fail(CORRUPT_LIBRARY, "Library group has no retained observation references");
    if (references.size() > BuildLibraryPage.MAX_REFERENCES_PER_GROUP)
      throw fail(LIMIT_EXCEEDED, "Group reference budget exceeded");
    budget.references = Math.addExact(budget.references, references.size());
    if (budget.references > BuildLibraryPage.MAX_REFERENCES_PER_PAGE)
      throw fail(LIMIT_EXCEEDED, "Page reference budget exceeded; reduce page size");
    var grouped =
        BuildLibraryGrouping.group(references, BuildLibraryPage.MAX_REFERENCES_PER_GROUP)
            .getFirst();
    var row =
        new BuildLibraryProjection.Row(
            grouped,
            group.seen(),
            local(c, candidate.id()),
            BuildLibraryProjection.advertisement(group.key(), states),
            regionalAdvertisements(group.key(), states));
    return new BuildLibraryPage.Entry(candidate.first(), row);
  }

  private static Map<String, BuildLibraryProjection.Advertisement> regionalAdvertisements(
      BuildReferenceGroupKey key, List<BuildLibraryRegionalState> states) {
    var facts = new TreeMap<String, BuildLibraryProjection.Advertisement>();
    for (var state : states)
      facts.put(state.scope().region(), BuildLibraryProjection.advertisement(key, List.of(state)));
    return Map.copyOf(facts);
  }

  private <T> T readTx(CancellationToken token, SqliteBuildLibraryRepository.Work<T> operation) {
    return library.tx(
        token,
        c -> {
          long started = nanoClock.getAsLong();
          boolean[] interrupted = {false};
          int interval = (int) Math.min(100, maximumSqlInstructions);
          ProgressHandler.setHandler(
              c,
              interval,
              new ProgressHandler() {
                long instructions;

                @Override
                protected int progress() {
                  instructions += interval;
                  if (token.isCancellationRequested()
                      || instructions > maximumSqlInstructions
                      || nanoClock.getAsLong() - started
                          >= java.time.Duration.ofSeconds(30).toNanos()) {
                    interrupted[0] = true;
                    return 1;
                  }
                  return 0;
                }
              });
          try {
            T result = operation.run(c);
            token.throwIfCancellationRequested();
            if (nanoClock.getAsLong() - started >= java.time.Duration.ofSeconds(30).toNanos())
              throw fail(LIMIT_EXCEEDED, "Library read exceeded bounded elapsed work");
            return result;
          } catch (SQLException failure) {
            token.throwIfCancellationRequested();
            if (interrupted[0])
              throw new BuildLibraryException(
                  LIMIT_EXCEEDED, "Library read exceeded bounded SQL or elapsed work", failure);
            throw failure;
          } finally {
            ProgressHandler.clearHandler(c);
          }
        });
  }

  private List<BuildLibraryRegionalState> states(Connection c, String product) throws SQLException {
    var scopes = new TreeSet<String>();
    try (var q =
        c.prepareStatement(
            "SELECT region FROM build_library_checks WHERE product_id=? UNION SELECT h.region FROM"
                + " build_library_registrations r JOIN discovery_history_events h ON"
                + " h.event_id=r.event_id WHERE h.product_id=?")) {
      q.setString(1, product);
      q.setString(2, product);
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          if (scopes.size() >= 64 && !scopes.contains(rows.getString(1)))
            throw fail(LIMIT_EXCEEDED, "Product regional projection limit exceeded");
          scopes.add(rows.getString(1));
        }
      }
    }
    var states = new ArrayList<BuildLibraryRegionalState>();
    for (var region : scopes)
      states.add(library.regional(c, new BuildLibraryScope(product, region)).state());
    return List.copyOf(states);
  }

  private static String localPredicate(String groupExpression) {
    return "EXISTS(SELECT 1 FROM build_library_snapshot_groups x WHERE x.group_id="
        + groupExpression
        + " AND (EXISTS(SELECT 1 FROM retention_scopes p WHERE p.snapshot_id=x.snapshot_id AND"
        + " p.kind IN ('ROOT_METADATA','INSTALL_METADATA')) OR EXISTS(SELECT 1 FROM"
        + " build_open_admissions a WHERE a.snapshot_id=x.snapshot_id) OR EXISTS(SELECT 1 FROM"
        + " build_object_memberships m WHERE m.snapshot_id=x.snapshot_id AND (m.role IN"
        + " ('ROOT_MANIFEST','INSTALL_MANIFEST') OR (substr(m.role,1,4)='EF1_' AND"
        + " length(m.role)=47)))))";
  }

  private BuildLibraryLocalFact local(Connection c, String group) throws SQLException {
    boolean prep = false, payload = false;
    try (var q =
        c.prepareStatement(
            "SELECT x.snapshot_id,x.versions_digest,x.record_sha256,s.digest FROM"
                + " build_library_snapshot_groups x LEFT JOIN snapshot_sources s ON"
                + " s.snapshot_id=x.snapshot_id AND s.position=0 WHERE x.group_id=?")) {
      q.setString(1, group);
      try (var rows = q.executeQuery()) {
        while (rows.next()) {
          String snapshot = rows.getString(1), digest = rows.getString(2);
          if (!digest.equals(rows.getString(4))
              || !hash("library-compatible-snapshot-v1", snapshot, group, digest)
                  .equals(rows.getString(3)))
            throw fail(CORRUPT_LIBRARY, "Exact local compatibility origin differs");
          try (var facts =
              c.prepareStatement(
                  "SELECT EXISTS(SELECT 1 FROM retention_scopes WHERE snapshot_id=? AND kind IN"
                      + " ('ROOT_METADATA','INSTALL_METADATA')) OR EXISTS(SELECT 1 FROM"
                      + " build_object_memberships WHERE snapshot_id=? AND role IN"
                      + " ('ROOT_MANIFEST','INSTALL_MANIFEST')) OR EXISTS(SELECT 1 FROM"
                      + " build_open_admissions a WHERE a.snapshot_id=?),EXISTS(SELECT 1 FROM"
                      + " build_object_memberships WHERE snapshot_id=? AND substr(role,1,4)='EF1_'"
                      + " AND length(role)=47)")) {
            facts.setString(1, snapshot);
            facts.setString(2, snapshot);
            facts.setString(3, snapshot);
            facts.setString(4, snapshot);
            try (var fact = facts.executeQuery()) {
              prep |= fact.getInt(1) != 0;
              payload |= fact.getInt(2) != 0;
            }
          }
        }
      }
    }
    return new BuildLibraryLocalFact(prep, payload);
  }

  private static long revision(Connection c) throws SQLException {
    try (var q = c.createStatement();
        var r = q.executeQuery("SELECT revision FROM build_library_revision WHERE singleton=1")) {
      if (!r.next() || r.getLong(1) < 0) throw fail(CORRUPT_LIBRARY, "Library revision is missing");
      return r.getLong(1);
    }
  }

  private record Candidate(String id, long first) {}

  private final class Budget {
    final Set<UUID> charged = new HashSet<>();
    long bytes;
    int references;

    void chargeKey(long count) {
      bytes = Math.addExact(bytes, count);
      if (bytes > pageBytes)
        throw fail(LIMIT_EXCEEDED, "Literal search exceeded bounded catalog metadata work");
    }

    void charge(UUID id, int count) {
      if (charged.add(id)) {
        bytes = Math.addExact(bytes, count);
        if (bytes > pageBytes)
          throw fail(LIMIT_EXCEEDED, "Page metadata budget exceeded; reduce page size");
      }
    }
  }

  private final class Cache {
    final LinkedHashMap<UUID, Cached> values = new LinkedHashMap<>(16, 0.75f, true);
    long bytes;
    int references;

    List<BuildLibraryReference> get(Connection c, UUID id, Budget budget) throws SQLException {
      var old = values.get(id);
      if (old != null) {
        budget.charge(id, old.bytes());
        return old.references();
      }
      var header =
          history
              .header(c, id)
              .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Library observation header is missing"));
      budget.charge(id, header.record().length);
      var projected =
          DiscoveryHistoryRecords.libraryReferences(header.record(), header.summary().sequence());
      var cached = new Cached(header.record().length, projected);
      if (cached.bytes() <= MAXIMUM_CACHE_METADATA_BYTES && projected.size() <= 128) {
        while (!values.isEmpty()
            && (bytes + cached.bytes() > MAXIMUM_CACHE_METADATA_BYTES
                || references + projected.size() > 128)) {
          var key = values.keySet().iterator().next();
          var removed = values.remove(key);
          bytes -= removed.bytes();
          references -= removed.references().size();
        }
        values.put(id, cached);
        bytes += cached.bytes();
        references += projected.size();
      }
      return projected;
    }
  }

  private record Cached(int bytes, List<BuildLibraryReference> references) {}

  private static String hash(String... fields) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (var field : fields) {
        byte[] text = field.getBytes(StandardCharsets.UTF_8);
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(text.length).array());
        hash.update(text);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static BuildLibraryException fail(BuildLibraryException.Code code, String message) {
    return new BuildLibraryException(code, message);
  }
}
