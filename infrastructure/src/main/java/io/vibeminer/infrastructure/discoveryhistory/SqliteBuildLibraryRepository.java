package io.vibeminer.infrastructure.discoveryhistory;

import static io.vibeminer.application.buildlibrary.BuildLibraryException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.*;
import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Durable local discovery facts. No payload/metadata acquisition or source-proof mutation. */
public final class SqliteBuildLibraryRepository
    implements BuildLibraryRepository, BuildLibraryProductChecks {
  public static final int MAXIMUM_CHECK_TICKETS = 100000;
  private final SqliteCatalog catalog;
  private final SqliteDiscoveryHistoryRepository history;
  private final int maximumTickets;

  public SqliteBuildLibraryRepository(SqliteCatalog catalog) {
    this(catalog, MAXIMUM_CHECK_TICKETS);
  }

  public SqliteBuildLibraryRepository(SqliteCatalog catalog, int maximumTickets) {
    this.catalog = Objects.requireNonNull(catalog);
    history = new SqliteDiscoveryHistoryRepository(catalog);
    if (maximumTickets < 1 || maximumTickets > MAXIMUM_CHECK_TICKETS)
      throw new IllegalArgumentException("Check ticket limit1..100000 required");
    this.maximumTickets = maximumTickets;
  }

  @Override
  public BuildLibraryCheckTicket beginCheck(
      BuildLibraryScope scope, Instant at, CancellationToken token) {
    if (scope == null || at == null || token == null)
      throw fail(INVALID_REQUEST, "Exact scope, time and cancellation required");
    return tx(
        token,
        c -> {
          requireCapacity(c);
          long next = nextAdmission(c);
          var ticket = new BuildLibraryCheckTicket(UUID.randomUUID(), scope, next, at);
          var check = new Check(ticket, "PENDING", Optional.empty(), Optional.empty());
          try (var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " build_library_checks(attempt_id,product_id,region,ordinal,requested_at,status,event_id,completed_at,record_sha256)"
                      + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            bindCheck(q, check);
            q.executeUpdate();
          }
          return ticket;
        });
  }

  @Override
  public BuildLibraryProductCheckTicket beginProductCheck(
      String product, Instant at, CancellationToken token) {
    if (product == null || at == null || token == null)
      throw fail(INVALID_REQUEST, "Exact product/time required");
    try {
      new DiscoveryHistoryFilter(product, Optional.empty(), Optional.empty());
    } catch (IllegalArgumentException error) {
      throw fail(INVALID_REQUEST, "Invalid raw product identity");
    }
    return tx(
        token,
        c -> {
          requireCapacity(c);
          var ticket =
              new BuildLibraryProductCheckTicket(UUID.randomUUID(), product, nextAdmission(c), at);
          try (var q =
              c.prepareStatement("INSERT INTO build_library_product_checks VALUES(?,?,?,?,?)")) {
            q.setString(1, ticket.attemptId().toString());
            q.setString(2, product);
            q.setLong(3, ticket.ordinal());
            q.setString(4, at.toString());
            q.setString(5, productHash(ticket));
            q.executeUpdate();
          }
          return ticket;
        });
  }

  @Override
  public BuildLibraryCheckTicket deriveRegionalCheck(
      BuildLibraryProductCheckTicket parent, String region, CancellationToken token) {
    if (parent == null || region == null || token == null)
      throw fail(INVALID_REQUEST, "Exact product parent and region required");
    BuildLibraryScope scope;
    try {
      scope = new BuildLibraryScope(parent.productId(), region);
    } catch (IllegalArgumentException error) {
      throw fail(INVALID_REQUEST, "Invalid region identity");
    }
    return tx(
        token,
        c -> {
          var stored =
              product(c, parent.attemptId())
                  .orElseThrow(() -> fail(INVALID_CHECK_STATE, "Product check is not registered"));
          if (!stored.equals(parent))
            throw fail(INVALID_CHECK_STATE, "Product parent facts differ");
          UUID id =
              UUID.nameUUIDFromBytes(
                  hash("library-region-child-v1", parent.attemptId(), region)
                      .getBytes(StandardCharsets.US_ASCII));
          var old = readCheck(c, id);
          var ticket =
              new BuildLibraryCheckTicket(id, scope, parent.ordinal(), parent.requestedAt());
          if (old.isPresent()) {
            if (!old.get().ticket().equals(ticket))
              throw fail(CORRUPT_LIBRARY, "Derived check identity conflicts");
            requireParent(c, id, parent);
            return ticket;
          }
          requireCapacity(c);
          var check = new Check(ticket, "PENDING", Optional.empty(), Optional.empty());
          try (var q =
              c.prepareStatement(
                  "INSERT INTO build_library_checks VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            bindCheck(q, check);
            q.setString(10, parent.attemptId().toString());
            q.setString(11, parentHash(parent, ticket));
            q.executeUpdate();
          }
          return ticket;
        });
  }

  @Override
  public List<BuildLibraryScope> knownScopes(String product, CancellationToken token) {
    if (product == null || token == null) throw fail(INVALID_REQUEST, "Exact product required");
    try {
      new DiscoveryHistoryFilter(product, Optional.empty(), Optional.empty());
    } catch (IllegalArgumentException error) {
      throw fail(INVALID_REQUEST, "Invalid product identity");
    }
    return tx(
        token,
        c -> {
          var scopes = new ArrayList<BuildLibraryScope>();
          try (var q =
              c.prepareStatement(
                  "SELECT DISTINCT region FROM build_library_checks WHERE product_id=? ORDER BY"
                      + " region LIMIT 65")) {
            q.setString(1, product);
            try (var rows = q.executeQuery()) {
              while (rows.next()) scopes.add(new BuildLibraryScope(product, rows.getString(1)));
            }
          }
          if (scopes.size() > 64) throw fail(LIMIT_EXCEEDED, "Known regional scope limit exceeded");
          return List.copyOf(scopes);
        });
  }

  @Override
  public BuildLibraryRegistration recordObservation(
      UUID eventId,
      BuildLibraryRegistration.Kind kind,
      Optional<BuildLibraryCheckTicket> check,
      Instant at,
      CancellationToken token) {
    if (eventId == null
        || kind == null
        || check == null
        || at == null
        || token == null
        || (kind == BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK) != check.isPresent())
      throw fail(INVALID_REQUEST, "Exact observation and registration kind/check required");
    return tx(
        token,
        c -> {
          var existing = registration(c, eventId, token);
          if (existing.isPresent()) {
            var stored = existing.get();
            if (stored.kind() != kind || !stored.check().equals(check))
              throw fail(
                  IMMUTABLE_REGISTRATION_CONFLICT,
                  "Observation already registered with another import/check identity");
            return stored;
          }
          var event =
              history
                  .readEvent(c, eventId, token)
                  .orElseThrow(() -> fail(NOT_FOUND, "Committed observation was not found"));
          long sequence = eventSequence(c, eventId);
          var references = BuildLibraryReference.from(event, sequence);
          var registration = new BuildLibraryRegistration(eventId, kind, check, at, references);
          var scope = new BuildLibraryScope(event.productId(), event.region());
          boolean hadBaseline = baseline(c, scope).isPresent();
          if (check.isPresent()) {
            var active = requireTicket(c, check.get());
            if (!active.status().equals("PENDING"))
              throw fail(INVALID_CHECK_STATE, "Check already finished");
          }
          var groups = uniqueGroups(references);
          try (var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " build_library_registrations(event_id,kind,check_id,recorded_at,group_count,record_sha256)"
                      + " VALUES(?,?,?,?,?,?)")) {
            q.setString(1, eventId.toString());
            q.setString(2, kind.name());
            q.setString(3, check.map(t -> t.attemptId().toString()).orElse(null));
            q.setString(4, at.toString());
            q.setInt(5, groups.size());
            q.setString(6, registrationHash(registration, groups));
            q.executeUpdate();
          }
          for (var reference : groups.values()) {
            token.throwIfCancellationRequested();
            var old = group(c, reference.key().identity());
            if (old.isPresent() && !old.get().key().equals(reference.key()))
              throw fail(CORRUPT_LIBRARY, "Group digest has conflicting exact references");
            if (old.isEmpty()) {
              var state =
                  BuildLibrarySeenPolicy.observe(
                      Optional.empty(),
                      reference,
                      kind == BuildLibraryRegistration.Kind.SUCCESSFUL_CHECK && hadBaseline,
                      at);
              byte[] key = BuildLibraryRecords.key(reference.key());
              try (var q =
                  c.prepareStatement(
                      "INSERT INTO build_library_groups VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                q.setString(1, state.groupId());
                q.setString(2, reference.key().productId());
                q.setBytes(3, key);
                q.setString(4, digest(key));
                q.setString(5, eventId.toString());
                q.setInt(6, reference.versionsRowOrdinal());
                q.setLong(7, sequence);
                q.setString(8, state.firstSeenAt().toString());
                q.setInt(9, state.eligibleAsNew() ? 1 : 0);
                q.setString(10, null);
                q.setString(11, seenHash(state, reference.versionsRowOrdinal()));
                q.executeUpdate();
              }
            }
            try (var q =
                c.prepareStatement("INSERT INTO build_library_event_groups VALUES(?,?,?)")) {
              q.setString(1, eventId.toString());
              q.setString(2, reference.key().identity());
              q.setInt(3, reference.versionsRowOrdinal());
              q.executeUpdate();
            }
          }
          establishCompatibleOrigins(c, event, references);
          if (check.isPresent()) {
            finish(c, new Check(check.get(), "SUCCESS", Optional.of(eventId), Optional.of(at)));
            if (!hadBaseline) {
              var baseline = new BuildLibraryRegionalBaseline(scope, eventId, sequence, at);
              try (var q =
                  c.prepareStatement("INSERT INTO build_library_baselines VALUES(?,?,?,?,?,?)")) {
                q.setString(1, scope.productId());
                q.setString(2, scope.region());
                q.setString(3, eventId.toString());
                q.setLong(4, sequence);
                q.setString(5, at.toString());
                q.setString(6, baselineHash(baseline));
                q.executeUpdate();
              }
            }
            updateRegional(
                c,
                check.get(),
                BuildLibraryRegionalState.CheckState.SUCCESS,
                Optional.of(eventId),
                at);
          }
          return registration;
        });
  }

  @Override
  public BuildLibraryRegionalState recordFailedScope(
      BuildLibraryCheckTicket ticket, Failure failure, Instant when, CancellationToken token) {
    if (ticket == null || failure == null || when == null || token == null)
      throw fail(INVALID_REQUEST, "Exact ticket/failure/time required");
    return tx(
        token,
        c -> {
          var stored = requireTicket(c, ticket);
          String status = failure.name();
          if (!stored.status().equals("PENDING")) {
            if (!stored.status().equals(status) || !stored.completedAt().equals(Optional.of(when)))
              throw fail(
                  INVALID_CHECK_STATE, "Check already finished with another terminal result");
            return regional(c, ticket.scope()).state();
          }
          finish(c, new Check(ticket, status, Optional.empty(), Optional.of(when)));
          var old = regional(c, ticket.scope());
          updateRegional(
              c,
              ticket,
              BuildLibraryRegionalState.CheckState.valueOf(status),
              old.state().latestSuccessfulEventId(),
              when);
          return regional(c, ticket.scope()).state();
        });
  }

  @Override
  public Optional<BuildLibrarySeenState> findSeen(String groupId, CancellationToken token) {
    requireGroupId(groupId);
    if (token == null) throw fail(INVALID_REQUEST, "Cancellation required");
    return tx(token, c -> group(c, groupId).map(Group::seen));
  }

  @Override
  public BuildLibrarySeenState acknowledgeOpen(
      BuildLibraryReference reference, Instant at, CancellationToken token) {
    if (reference == null || at == null || token == null)
      throw fail(INVALID_REQUEST, "Exact successfully opened reference/time required");
    return tx(
        token,
        c -> {
          var registration =
              registration(c, reference.eventId(), token)
                  .orElseThrow(() -> fail(NOT_FOUND, "Opened reference was not registered"));
          if (!registration.references().contains(reference))
            throw fail(INVALID_REQUEST, "Opened reference differs from its immutable observation");
          var old =
              group(c, reference.key().identity())
                  .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Registered group state is missing"));
          var updated = BuildLibrarySeenPolicy.acknowledgeOpen(old.seen(), reference, at);
          if (!updated.equals(old.seen()))
            try (var q =
                c.prepareStatement(
                    "UPDATE build_library_groups SET acknowledged_at=?,seen_sha256=? WHERE"
                        + " group_id=?")) {
              q.setString(1, updated.acknowledgedAt().orElseThrow().toString());
              q.setString(2, seenHash(updated, old.firstRowOrdinal()));
              q.setString(3, updated.groupId());
              q.executeUpdate();
            }
          return updated;
        });
  }

  @Override
  public Optional<BuildLibraryRegionalBaseline> baseline(
      BuildLibraryScope scope, CancellationToken token) {
    if (scope == null || token == null) throw fail(INVALID_REQUEST, "Exact scope required");
    return tx(token, c -> baseline(c, scope));
  }

  @Override
  public BuildLibraryRegionalState regionalState(BuildLibraryScope scope, CancellationToken token) {
    if (scope == null || token == null) throw fail(INVALID_REQUEST, "Exact scope required");
    return tx(token, c -> regional(c, scope).state());
  }

  private Optional<BuildLibraryRegistration> registration(
      Connection c, UUID id, CancellationToken token) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT kind,check_id,recorded_at,group_count,record_sha256 FROM"
                + " build_library_registrations WHERE event_id=?")) {
      q.setString(1, id.toString());
      try (var rows = q.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        var kind = BuildLibraryRegistration.Kind.valueOf(rows.getString(1));
        Optional<BuildLibraryCheckTicket> ticket = Optional.empty();
        if (rows.getString(2) != null) {
          var check =
              readCheck(c, UUID.fromString(rows.getString(2)))
                  .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Registration check is missing"));
          if (!check.status().equals("SUCCESS") || !check.eventId().equals(Optional.of(id)))
            throw fail(CORRUPT_LIBRARY, "Registration check completion differs");
          ticket = Optional.of(check.ticket());
        }
        var event =
            history
                .readEvent(c, id, token)
                .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Registered observation is missing"));
        var value =
            new BuildLibraryRegistration(
                id,
                kind,
                ticket,
                Instant.parse(rows.getString(3)),
                BuildLibraryReference.from(event, eventSequence(c, id)));
        var groups = uniqueGroups(value.references());
        if (groups.size() != rows.getInt(4)
            || !registrationHash(value, groups).equals(rows.getString(5)))
          throw fail(CORRUPT_LIBRARY, "Registration facts differ from immutable record");
        int count = 0;
        try (var refs =
            c.prepareStatement(
                "SELECT group_id,first_row_ordinal FROM build_library_event_groups WHERE"
                    + " event_id=?")) {
          refs.setString(1, id.toString());
          try (var ref = refs.executeQuery()) {
            while (ref.next()) {
              var expected = groups.get(ref.getString(1));
              if (expected == null
                  || expected.versionsRowOrdinal() != ref.getInt(2)
                  || group(c, expected.key().identity()).isEmpty())
                throw fail(CORRUPT_LIBRARY, "Registered group reference differs");
              count++;
            }
          }
        }
        if (count != groups.size())
          throw fail(CORRUPT_LIBRARY, "Registration group references are incomplete");
        return Optional.of(value);
      }
    }
  }

  Optional<Group> group(Connection c, String id) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT *,length(key_record) AS key_length FROM build_library_groups WHERE"
                + " group_id=?")) {
      q.setString(1, id);
      try (var row = q.executeQuery()) {
        if (!row.next()) return Optional.empty();
        if (row.getLong("key_length") < 1
            || row.getLong("key_length") > BuildLibraryRecords.MAX_KEY_BYTES)
          throw fail(CORRUPT_LIBRARY, "Group key size is invalid");
        byte[] bytes = row.getBytes("key_record");
        if (!digest(bytes).equals(row.getString("key_sha256")))
          throw fail(CORRUPT_LIBRARY, "Group key checksum differs");
        var key = BuildLibraryRecords.key(bytes);
        if (!key.identity().equals(id) || !key.productId().equals(row.getString("product_id")))
          throw fail(CORRUPT_LIBRARY, "Group indexed identity differs");
        int eligible = row.getInt("new_eligible");
        if (eligible != 0 && eligible != 1) throw fail(CORRUPT_LIBRARY, "Invalid New state");
        String ack = row.getString("acknowledged_at");
        var seen =
            new BuildLibrarySeenState(
                id,
                UUID.fromString(row.getString("first_event_id")),
                row.getLong("first_observation_sequence"),
                Instant.parse(row.getString("first_seen_at")),
                eligible == 1,
                ack == null ? Optional.empty() : Optional.of(Instant.parse(ack)));
        int ordinal = row.getInt("first_row_ordinal");
        if (!seenHash(seen, ordinal).equals(row.getString("seen_sha256")))
          throw fail(CORRUPT_LIBRARY, "First-seen/acknowledgment checksum differs");
        try (var ref =
            c.prepareStatement(
                "SELECT g.first_row_ordinal,h.event_sequence FROM build_library_event_groups g JOIN"
                    + " discovery_history_events h ON h.event_id=g.event_id WHERE g.event_id=? AND"
                    + " g.group_id=?")) {
          ref.setString(1, seen.firstEventId().toString());
          ref.setString(2, id);
          try (var r = ref.executeQuery()) {
            if (!r.next()
                || r.getInt(1) != ordinal
                || r.getLong(2) != seen.firstObservationSequence())
              throw fail(CORRUPT_LIBRARY, "First-seen source reference is missing");
          }
        }
        return Optional.of(new Group(key, seen, ordinal));
      }
    }
  }

  private Optional<BuildLibraryRegionalBaseline> baseline(Connection c, BuildLibraryScope scope)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT * FROM build_library_baselines WHERE product_id=? AND region=?")) {
      scope(q, scope);
      try (var r = q.executeQuery()) {
        if (!r.next()) return Optional.empty();
        var value =
            new BuildLibraryRegionalBaseline(
                scope,
                UUID.fromString(r.getString("event_id")),
                r.getLong("observation_sequence"),
                Instant.parse(r.getString("recorded_at")));
        if (!baselineHash(value).equals(r.getString("record_sha256"))
            || eventSequence(c, value.eventId()) != value.observationSequence())
          throw fail(CORRUPT_LIBRARY, "Scope baseline checksum/reference differs");
        requireSuccessfulScope(c, value.eventId(), scope);
        return Optional.of(value);
      }
    }
  }

  Regional regional(Connection c, BuildLibraryScope scope) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT * FROM build_library_region_states WHERE product_id=? AND region=?")) {
      scope(q, scope);
      try (var row = q.executeQuery()) {
        if (!row.next())
          return new Regional(
              0,
              new BuildLibraryRegionalState(
                  scope,
                  BuildLibraryRegionalState.CheckState.NOT_CHECKED,
                  Optional.empty(),
                  Set.of(),
                  Optional.empty()));
        long ordinal = row.getLong("finished_ordinal");
        var check =
            readCheck(c, UUID.fromString(row.getString("check_id")))
                .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Scope completed check is missing"));
        if (!check.ticket().scope().equals(scope)
            || check.ticket().ordinal() != ordinal
            || !check.status().equals(row.getString("check_state"))
            || !check.completedAt().equals(Optional.of(Instant.parse(row.getString("checked_at")))))
          throw fail(CORRUPT_LIBRARY, "Scope terminal check differs");
        String success = row.getString("latest_successful_event_id");
        Optional<UUID> event =
            success == null ? Optional.empty() : Optional.of(UUID.fromString(success));
        if (check.status().equals("SUCCESS") && !check.eventId().equals(event))
          throw fail(CORRUPT_LIBRARY, "Scope success source differs");
        if (!regionalHash(
                scope,
                ordinal,
                check.ticket().attemptId(),
                check.status(),
                event,
                check.completedAt().orElseThrow())
            .equals(row.getString("record_sha256")))
          throw fail(CORRUPT_LIBRARY, "Scope state checksum differs");
        var groups = new HashSet<String>();
        if (event.isPresent()) {
          requireSuccessfulScope(c, event.get(), scope);
          try (var refs =
              c.prepareStatement(
                  "SELECT group_id FROM build_library_event_groups WHERE event_id=?")) {
            refs.setString(1, event.get().toString());
            try (var values = refs.executeQuery()) {
              while (values.next()) {
                if (!groups.add(values.getString(1)) || group(c, values.getString(1)).isEmpty())
                  throw fail(CORRUPT_LIBRARY, "Scope group source differs");
              }
            }
          }
          try (var count =
              c.prepareStatement(
                  "SELECT group_count FROM build_library_registrations WHERE event_id=?")) {
            count.setString(1, event.get().toString());
            try (var r = count.executeQuery()) {
              if (!r.next() || r.getInt(1) != groups.size())
                throw fail(CORRUPT_LIBRARY, "Scope group set is incomplete");
            }
          }
        }
        return new Regional(
            ordinal,
            new BuildLibraryRegionalState(
                scope,
                BuildLibraryRegionalState.CheckState.valueOf(check.status()),
                event,
                groups,
                check.completedAt()));
      }
    }
  }

  private void updateRegional(
      Connection c,
      BuildLibraryCheckTicket ticket,
      BuildLibraryRegionalState.CheckState state,
      Optional<UUID> success,
      Instant at)
      throws SQLException {
    // Product captures share one admission order even before their region set is known.
    try (var q =
        c.prepareStatement(
            "SELECT COALESCE(max(ordinal),0) FROM build_library_product_checks WHERE"
                + " product_id=?")) {
      q.setString(1, ticket.scope().productId());
      try (var row = q.executeQuery()) {
        if (ticket.ordinal() < row.getLong(1)) return;
      }
    }
    var old = regional(c, ticket.scope());
    if (ticket.ordinal() <= old.ordinal()) return;
    try (var q =
        c.prepareStatement(
            "INSERT INTO build_library_region_states VALUES(?,?,?,?,?,?,?,?) ON"
                + " CONFLICT(product_id,region) DO UPDATE SET"
                + " finished_ordinal=excluded.finished_ordinal,check_id=excluded.check_id,latest_successful_event_id=excluded.latest_successful_event_id,check_state=excluded.check_state,checked_at=excluded.checked_at,record_sha256=excluded.record_sha256")) {
      q.setString(1, ticket.scope().productId());
      q.setString(2, ticket.scope().region());
      q.setLong(3, ticket.ordinal());
      q.setString(4, ticket.attemptId().toString());
      q.setString(5, success.map(UUID::toString).orElse(null));
      q.setString(6, state.name());
      q.setString(7, at.toString());
      q.setString(
          8,
          regionalHash(
              ticket.scope(), ticket.ordinal(), ticket.attemptId(), state.name(), success, at));
      q.executeUpdate();
    }
  }

  private void establishCompatibleOrigins(
      Connection c, DiscoveryEvent event, List<BuildLibraryReference> references)
      throws SQLException {
    var candidates = new HashMap<String, Set<String>>();
    for (var reference : references)
      candidates
          .computeIfAbsent(reference.snapshotId(), ignored -> new HashSet<>())
          .add(reference.key().identity());
    for (var entry : candidates.entrySet()) {
      if (entry.getValue().size() != 1) continue;
      try (var source =
          c.prepareStatement(
              "SELECT digest,url FROM snapshot_sources WHERE snapshot_id=? AND position=0")) {
        source.setString(1, entry.getKey());
        try (var row = source.executeQuery()) {
          if (!row.next()
              || !event.versions().source().sha256().equals(row.getString(1))
              || !event.versions().source().sourceUrl().equals(row.getString(2))) continue;
        }
      }
      String group = entry.getValue().iterator().next(),
          sha =
              hash(
                  "library-compatible-snapshot-v1",
                  entry.getKey(),
                  group,
                  event.versions().source().sha256());
      try (var old =
          c.prepareStatement(
              "SELECT group_id,versions_digest,record_sha256 FROM build_library_snapshot_groups"
                  + " WHERE snapshot_id=?")) {
        old.setString(1, entry.getKey());
        try (var row = old.executeQuery()) {
          if (row.next()) {
            if (!group.equals(row.getString(1))
                || !event.versions().source().sha256().equals(row.getString(2))
                || !sha.equals(row.getString(3)))
              throw fail(
                  CORRUPT_LIBRARY,
                  "Canonical preparation compatibility conflicts with original metadata");
            continue;
          }
        }
      }
      try (var q =
          c.prepareStatement("INSERT INTO build_library_snapshot_groups VALUES(?,?,?,?)")) {
        q.setString(1, entry.getKey());
        q.setString(2, group);
        q.setString(3, event.versions().source().sha256());
        q.setString(4, sha);
        q.executeUpdate();
      }
    }
  }

  private Optional<BuildLibraryProductCheckTicket> product(Connection c, UUID id)
      throws SQLException {
    try (var q =
        c.prepareStatement("SELECT * FROM build_library_product_checks WHERE attempt_id=?")) {
      q.setString(1, id.toString());
      try (var row = q.executeQuery()) {
        if (!row.next()) return Optional.empty();
        var ticket =
            new BuildLibraryProductCheckTicket(
                id,
                row.getString("product_id"),
                row.getLong("ordinal"),
                Instant.parse(row.getString("requested_at")));
        if (!productHash(ticket).equals(row.getString("record_sha256")))
          throw fail(CORRUPT_LIBRARY, "Product admission checksum differs");
        return Optional.of(ticket);
      }
    }
  }

  private void requireParent(Connection c, UUID child, BuildLibraryProductCheckTicket parent)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT parent_attempt_id FROM build_library_checks WHERE attempt_id=?")) {
      q.setString(1, child.toString());
      try (var row = q.executeQuery()) {
        if (!row.next() || !parent.attemptId().toString().equals(row.getString(1)))
          throw fail(CORRUPT_LIBRARY, "Regional check changed its original parent");
      }
    }
  }

  private void requireCapacity(Connection c) throws SQLException {
    if (Math.addExact(
            scalar(c, "SELECT count(*) FROM build_library_checks"),
            scalar(c, "SELECT count(*) FROM build_library_product_checks"))
        >= maximumTickets)
      throw fail(LIMIT_EXCEEDED, "Durable check ticket limit reached; no tickets were pruned");
  }

  private long nextAdmission(Connection c) throws SQLException {
    long previous =
        scalar(c, "SELECT ordinal FROM build_library_admission_counter WHERE singleton=1");
    long maximum =
        scalar(
            c,
            "SELECT max(value) FROM (SELECT COALESCE(max(ordinal),0) AS value FROM"
                + " build_library_checks UNION ALL SELECT COALESCE(max(ordinal),0) AS value FROM"
                + " build_library_product_checks)");
    if (previous < maximum)
      throw fail(CORRUPT_LIBRARY, "Global admission order is behind issued tickets");
    if (previous == Long.MAX_VALUE) throw fail(LIMIT_EXCEEDED, "Admission ordinal exhausted");
    long next = previous + 1;
    try (var q =
        c.prepareStatement(
            "UPDATE build_library_admission_counter SET ordinal=? WHERE singleton=1")) {
      q.setLong(1, next);
      if (q.executeUpdate() != 1)
        throw fail(CORRUPT_LIBRARY, "Global admission counter is missing");
    }
    return next;
  }

  private static String productHash(BuildLibraryProductCheckTicket ticket) {
    return hash(
        "library-product-check-v1",
        ticket.attemptId(),
        ticket.productId(),
        ticket.ordinal(),
        ticket.requestedAt());
  }

  private static String parentHash(
      BuildLibraryProductCheckTicket parent, BuildLibraryCheckTicket child) {
    return hash(
        "library-regional-parent-v1",
        parent.attemptId(),
        child.attemptId(),
        parent.productId(),
        child.scope().region(),
        parent.ordinal(),
        parent.requestedAt());
  }

  private Check requireTicket(Connection c, BuildLibraryCheckTicket ticket) throws SQLException {
    var check =
        readCheck(c, ticket.attemptId())
            .orElseThrow(() -> fail(INVALID_CHECK_STATE, "Check ticket is not registered"));
    if (!check.ticket().equals(ticket))
      throw fail(INVALID_CHECK_STATE, "Check ticket facts differ");
    return check;
  }

  private Optional<Check> readCheck(Connection c, UUID id) throws SQLException {
    try (var q = c.prepareStatement("SELECT * FROM build_library_checks WHERE attempt_id=?")) {
      q.setString(1, id.toString());
      try (var r = q.executeQuery()) {
        if (!r.next()) return Optional.empty();
        var ticket =
            new BuildLibraryCheckTicket(
                id,
                new BuildLibraryScope(r.getString("product_id"), r.getString("region")),
                r.getLong("ordinal"),
                Instant.parse(r.getString("requested_at")));
        String event = r.getString("event_id"), at = r.getString("completed_at");
        var check =
            new Check(
                ticket,
                r.getString("status"),
                event == null ? Optional.empty() : Optional.of(UUID.fromString(event)),
                at == null ? Optional.empty() : Optional.of(Instant.parse(at)));
        if (!checkHash(check).equals(r.getString("record_sha256"))
            || !Set.of("PENDING", "SUCCESS", "FAILED", "CANCELLED").contains(check.status())
            || check.status().equals("PENDING") != (check.completedAt().isEmpty())
            || check.status().equals("SUCCESS") != check.eventId().isPresent())
          throw fail(CORRUPT_LIBRARY, "Check ticket/terminal checksum differs");
        String parentId = r.getString("parent_attempt_id");
        if (parentId != null) {
          var parent =
              product(c, UUID.fromString(parentId))
                  .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Product parent is missing"));
          if (!ticket.scope().productId().equals(parent.productId())
              || ticket.ordinal() != parent.ordinal()
              || !ticket.requestedAt().equals(parent.requestedAt())
              || !parentHash(parent, ticket).equals(r.getString("parent_sha256")))
            throw fail(CORRUPT_LIBRARY, "Derived check parent differs");
        } else if (r.getString("parent_sha256") != null)
          throw fail(CORRUPT_LIBRARY, "Unexpected product parent checksum");
        return Optional.of(check);
      }
    }
  }

  private void finish(Connection c, Check check) throws SQLException {
    try (var q =
        c.prepareStatement(
            "UPDATE build_library_checks SET status=?,event_id=?,completed_at=?,record_sha256=?"
                + " WHERE attempt_id=? AND status='PENDING'")) {
      q.setString(1, check.status());
      q.setString(2, check.eventId().map(UUID::toString).orElse(null));
      q.setString(3, check.completedAt().orElseThrow().toString());
      q.setString(4, checkHash(check));
      q.setString(5, check.ticket().attemptId().toString());
      if (q.executeUpdate() != 1) throw fail(INVALID_CHECK_STATE, "Check is no longer pending");
    }
  }

  private static void bindCheck(PreparedStatement q, Check check) throws SQLException {
    var t = check.ticket();
    q.setString(1, t.attemptId().toString());
    q.setString(2, t.scope().productId());
    q.setString(3, t.scope().region());
    q.setLong(4, t.ordinal());
    q.setString(5, t.requestedAt().toString());
    q.setString(6, check.status());
    q.setString(7, check.eventId().map(UUID::toString).orElse(null));
    q.setString(8, check.completedAt().map(Instant::toString).orElse(null));
    q.setString(9, checkHash(check));
  }

  private void requireSuccessfulScope(Connection c, UUID event, BuildLibraryScope scope)
      throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT kind,check_id FROM build_library_registrations WHERE event_id=?")) {
      q.setString(1, event.toString());
      try (var row = q.executeQuery()) {
        if (!row.next() || !"SUCCESSFUL_CHECK".equals(row.getString(1)) || row.getString(2) == null)
          throw fail(CORRUPT_LIBRARY, "Successful scope registration is incomplete");
        var check =
            readCheck(c, UUID.fromString(row.getString(2)))
                .orElseThrow(() -> fail(CORRUPT_LIBRARY, "Successful scope check is missing"));
        if (!check.status().equals("SUCCESS")
            || !check.ticket().scope().equals(scope)
            || !check.eventId().equals(Optional.of(event)))
          throw fail(CORRUPT_LIBRARY, "Successful scope provenance differs");
      }
    }
  }

  private static LinkedHashMap<String, BuildLibraryReference> uniqueGroups(
      List<BuildLibraryReference> refs) {
    var groups = new LinkedHashMap<String, BuildLibraryReference>();
    for (var ref : refs) {
      var old = groups.putIfAbsent(ref.key().identity(), ref);
      if (old != null && !old.key().equals(ref.key()))
        throw fail(CORRUPT_LIBRARY, "Group key digest conflict");
    }
    return groups;
  }

  private static long eventSequence(Connection c, UUID id) throws SQLException {
    try (var q =
        c.prepareStatement(
            "SELECT event_sequence FROM discovery_history_events WHERE event_id=?")) {
      q.setString(1, id.toString());
      try (var r = q.executeQuery()) {
        if (!r.next() || r.getLong(1) < 1)
          throw fail(CORRUPT_LIBRARY, "Observation sequence is missing");
        return r.getLong(1);
      }
    }
  }

  private static void scope(PreparedStatement q, BuildLibraryScope scope) throws SQLException {
    q.setString(1, scope.productId());
    q.setString(2, scope.region());
  }

  private static String checkHash(Check c) {
    var t = c.ticket();
    return hash(
        "library-check-v1",
        t.attemptId(),
        t.scope().productId(),
        t.scope().region(),
        t.ordinal(),
        t.requestedAt(),
        c.status(),
        c.eventId().map(UUID::toString).orElse(""),
        c.completedAt().map(Instant::toString).orElse(""));
  }

  private static String seenHash(BuildLibrarySeenState s, int row) {
    return hash(
        "library-seen-v1",
        s.groupId(),
        s.firstEventId(),
        row,
        s.firstObservationSequence(),
        s.firstSeenAt(),
        s.eligibleAsNew(),
        s.acknowledgedAt().map(Instant::toString).orElse(""));
  }

  private static String baselineHash(BuildLibraryRegionalBaseline b) {
    return hash(
        "library-baseline-v1",
        b.scope().productId(),
        b.scope().region(),
        b.eventId(),
        b.observationSequence(),
        b.recordedAt());
  }

  private static String regionalHash(
      BuildLibraryScope s,
      long ordinal,
      UUID check,
      String state,
      Optional<UUID> event,
      Instant at) {
    return hash(
        "library-regional-v1",
        s.productId(),
        s.region(),
        ordinal,
        check,
        state,
        event.map(UUID::toString).orElse(""),
        at);
  }

  private static String registrationHash(
      BuildLibraryRegistration r, Map<String, BuildLibraryReference> groups) {
    var fields = new ArrayList<Object>();
    fields.addAll(
        List.of(
            "library-registration-v1",
            r.eventId(),
            r.kind(),
            r.check().map(t -> t.attemptId().toString()).orElse(""),
            r.recordedAt(),
            groups.size()));
    for (var id : new TreeSet<>(groups.keySet())) {
      fields.add(id);
      fields.add(groups.get(id).versionsRowOrdinal());
    }
    return hash(fields.toArray());
  }

  private static String hash(Object... fields) {
    try {
      var out = new ByteArrayOutputStream();
      try (var data = new DataOutputStream(out)) {
        for (var field : fields) {
          byte[] text = field.toString().getBytes(StandardCharsets.UTF_8);
          data.writeInt(text.length);
          data.write(text);
        }
      }
      return digest(out.toByteArray());
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (var q = c.createStatement();
        var r = q.executeQuery(sql)) {
      if (!r.next() || r.getLong(1) < 0) throw fail(CORRUPT_LIBRARY, "Invalid library accounting");
      return r.getLong(1);
    }
  }

  private static void requireGroupId(String id) {
    if (id == null || !id.matches("[0-9a-f]{64}"))
      throw fail(INVALID_REQUEST, "Exact group digest required");
  }

  private record Check(
      BuildLibraryCheckTicket ticket,
      String status,
      Optional<UUID> eventId,
      Optional<Instant> completedAt) {}

  record Group(BuildReferenceGroupKey key, BuildLibrarySeenState seen, int firstRowOrdinal) {}

  record Regional(long ordinal, BuildLibraryRegionalState state) {}

  @FunctionalInterface
  interface Work<T> {
    T run(Connection c) throws SQLException;
  }

  <T> T tx(CancellationToken token, Work<T> work) {
    token.throwIfCancellationRequested();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try {
        T result = work.run(c);
        token.throwIfCancellationRequested();
        c.commit();
        return result;
      } catch (SQLException | RuntimeException | Error error) {
        try {
          c.rollback();
        } catch (SQLException cleanup) {
          error.addSuppressed(cleanup);
        }
        throw error;
      }
    } catch (BuildLibraryException | CancellationException error) {
      throw error;
    } catch (SQLException error) {
      throw new BuildLibraryException(
          error.getErrorCode() == 5 ? BUSY : STORAGE_UNAVAILABLE,
          "Build library catalog operation failed",
          error);
    } catch (DiscoveryHistoryException error) {
      throw new BuildLibraryException(
          error.code() == DiscoveryHistoryException.Code.NOT_FOUND
              ? NOT_FOUND
              : error.code() == DiscoveryHistoryException.Code.STORAGE_UNAVAILABLE
                  ? STORAGE_UNAVAILABLE
                  : CORRUPT_LIBRARY,
          "Committed observation failed validation",
          error);
    } catch (RuntimeException error) {
      throw new BuildLibraryException(
          CORRUPT_LIBRARY, "Build library facts failed validation", error);
    }
  }

  private static BuildLibraryException fail(BuildLibraryException.Code code, String message) {
    return new BuildLibraryException(code, message);
  }
}
