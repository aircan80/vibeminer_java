package io.vibeminer.infrastructure.inventory;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.inventory.ManifestReferenceResolver.Resolution;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import java.sql.*;
import java.util.*;

/** Package-private provisional SQL. Only the source-replaying facade may publish a generation. */
final class InventoryStaging {
  record Limits(long maxRows, long maxDerivedBytes, int batchRows, long maxBatches) {
    Limits {
      if (maxRows < 0 || maxDerivedBytes < 1 || batchRows < 1 || batchRows > 4096 || maxBatches < 1)
        throw new IllegalArgumentException("Invalid inventory bounds");
    }
  }

  record Source(String role, byte[] proof) {
    Source {
      proof = proof.clone();
      if (proof.length < 1 || proof.length > 262144)
        throw new IllegalArgumentException("Bounded source proof required");
    }

    @Override
    public byte[] proof() {
      return proof.clone();
    }
  }

  record Reference(
      String role,
      long ordinal,
      long blockOrdinal,
      String key,
      OptionalLong declaredBytes,
      String rawFact) {
    Reference {
      Objects.requireNonNull(role);
      Objects.requireNonNull(declaredBytes);
      Objects.requireNonNull(rawFact);
      if (ordinal < 0
          || blockOrdinal < 0
          || key == null
          || !key.matches("(?:[0-9a-f]{18}|[0-9a-f]{32})")
          || declaredBytes.isPresent() && declaredBytes.getAsLong() < 0
          || rawFact.length() > 8192
          || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(rawFact))
        throw new IllegalArgumentException("Invalid inventory source reference");
    }
  }

  record Row(Reference reference, Optional<String> resolution) {}

  @FunctionalInterface
  interface Authority {
    void validate(Connection c) throws SQLException;
  }

  private final SqliteCatalog catalog;

  InventoryStaging(SqliteCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  String begin(
      String snapshot,
      String admission,
      String enArtifact,
      String enGeneration,
      String scope,
      List<Source> sources,
      Limits limits,
      CancellationToken token) {
    var roles = new HashSet<String>();
    for (var source : sources)
      if (!Set.of("ROOT", "INSTALL", "DOWNLOAD", "SIZE").contains(source.role())
          || !roles.add(source.role()))
        throw new IllegalArgumentException("Unknown or duplicate inventory family");
    if (!roles.containsAll(Set.of("ROOT", "INSTALL")))
      throw new IllegalArgumentException("ROOT and INSTALL required");
    long initial = 512;
    for (var source : sources) initial = Math.addExact(initial, source.proof().length + 128L);
    final long charged = initial;
    var id = UUID.randomUUID().toString();
    transaction(
        token,
        c -> {
          quota(c, 0, charged, limits);
          try (var count = c.createStatement();
              var rows = count.executeQuery("SELECT count(*) FROM build_inventory_generations")) {
            rows.next();
            if (rows.getLong(1) >= 1024)
              throw new IllegalStateException("Inventory generation quota exceeded");
          }
          try (var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " build_inventory_generations(id,snapshot_id,admission_id,en_artifact,en_generation,scope,status,derived_bytes)"
                      + " VALUES(?,?,?,?,?,?,'STAGING',?)")) {
            q.setString(1, id);
            q.setString(2, snapshot);
            q.setString(3, admission);
            q.setString(4, enArtifact);
            q.setString(5, enGeneration);
            q.setString(6, scope);
            q.setLong(7, charged);
            q.executeUpdate();
          }
          try (var q =
              c.prepareStatement(
                  "INSERT INTO build_inventory_sources(generation,role,proof) VALUES(?,?,?)")) {
            for (var source : sources) {
              q.setString(1, id);
              q.setString(2, source.role());
              q.setBytes(3, source.proof());
              q.addBatch();
            }
            q.executeBatch();
          }
        });
    return id;
  }

  record Ready(String id, long members) {}

  Optional<Ready> findReady(
      String snapshot,
      String admission,
      String enArtifact,
      String enGeneration,
      String scope,
      List<Source> sources,
      Authority authority,
      CancellationToken token) {
    var result = new ArrayList<Ready>();
    transaction(
        token,
        c -> {
          try (var q =
              c.prepareStatement(
                  "SELECT id,member_count FROM build_inventory_generations WHERE snapshot_id=? AND"
                      + " admission_id=? AND en_artifact=? AND en_generation=? AND scope=? AND"
                      + " status='READY' LIMIT 2")) {
            q.setString(1, snapshot);
            q.setString(2, admission);
            q.setString(3, enArtifact);
            q.setString(4, enGeneration);
            q.setString(5, scope);
            try (var r = q.executeQuery()) {
              while (r.next()) result.add(new Ready(r.getString(1), r.getLong(2)));
            }
          }
          if (result.size() > 1)
            throw new IllegalStateException("Duplicate authoritative inventory contexts");
          if (!result.isEmpty()) {
            var ready = result.getFirst();
            requireContext(
                c, ready.id(), snapshot, admission, enArtifact, enGeneration, scope, sources);
            authority.validate(c);
            try (var q =
                c.prepareStatement(
                    "SELECT count(*) FROM build_inventory_members WHERE generation=?")) {
              q.setString(1, ready.id());
              try (var r = q.executeQuery()) {
                r.next();
                if (r.getLong(1) != ready.members())
                  throw new IllegalStateException("Ready inventory member count changed");
              }
            }
          }
        });
    return result.stream().findFirst();
  }

  void discard(String id) {
    transaction(
        CancellationToken.none(),
        c -> {
          try (var q =
              c.prepareStatement(
                  "DELETE FROM build_inventory_generations WHERE id=? AND status='STAGING'")) {
            q.setString(1, id);
            q.executeUpdate();
          }
        });
  }

  void append(String id, List<Reference> rows, Limits limits, CancellationToken token) {
    rows = List.copyOf(rows);
    if (rows.size() > limits.batchRows())
      throw new IllegalArgumentException("Inventory batch limit");
    long work = 0;
    for (var r : rows) work = Math.addExact(work, 256L + r.rawFact().length() * 3L);
    final long bytes = work;
    final var batch = rows;
    transaction(
        token,
        c -> {
          staging(c, id);
          quota(c, batch.size(), bytes, limits);
          try (var q =
              c.prepareStatement(
                  "UPDATE build_inventory_generations SET"
                      + " row_count=row_count+?,derived_bytes=derived_bytes+?,batches=batches+1"
                      + " WHERE id=? AND row_count<=? AND derived_bytes<=? AND batches<?")) {
            q.setLong(1, batch.size());
            q.setLong(2, bytes);
            q.setString(3, id);
            q.setLong(4, limits.maxRows() - batch.size());
            q.setLong(5, limits.maxDerivedBytes() - bytes);
            q.setLong(6, limits.maxBatches());
            if (q.executeUpdate() != 1)
              throw new IllegalStateException("Inventory derived quota exceeded");
          }
          try (var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " build_inventory_references(generation,role,ordinal,block_ordinal,key_ref,declared_bytes,raw_fact)"
                      + " SELECT ?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM build_inventory_sources"
                      + " WHERE generation=? AND role=? AND complete=0)")) {
            for (var r : batch) {
              token.throwIfCancellationRequested();
              q.setString(1, id);
              q.setString(2, r.role());
              q.setLong(3, r.ordinal());
              q.setLong(4, r.blockOrdinal());
              q.setString(5, r.key());
              if (r.declaredBytes().isPresent()) q.setLong(6, r.declaredBytes().getAsLong());
              else q.setNull(6, Types.BIGINT);
              q.setString(7, r.rawFact());
              q.setString(8, id);
              q.setString(9, r.role());
              if (q.executeUpdate() != 1)
                throw new IllegalStateException("Missing or completed inventory source");
            }
          }
        });
  }

  void complete(String id, String role, long count, CancellationToken token) {
    transaction(
        token,
        c -> {
          staging(c, id);
          try (var q =
              c.prepareStatement(
                  "SELECT count(*),min(ordinal),max(ordinal) FROM build_inventory_references WHERE"
                      + " generation=? AND role=?")) {
            q.setString(1, id);
            q.setString(2, role);
            try (var r = q.executeQuery()) {
              r.next();
              if (r.getLong(1) != count
                  || count > 0 && (r.getLong(2) != 0 || r.getLong(3) != count - 1))
                throw new IllegalStateException("Incomplete manifest ordinals");
            }
          }
          try (var q =
              c.prepareStatement(
                  "UPDATE build_inventory_sources SET complete=1,expected_rows=? WHERE generation=?"
                      + " AND role=? AND complete=0")) {
            q.setLong(1, count);
            q.setString(2, id);
            q.setString(3, role);
            if (q.executeUpdate() != 1)
              throw new IllegalStateException("Source completion repeated or missing");
          }
        });
  }

  List<Row> page(String id, String role, long after, int maximum, CancellationToken token) {
    if (maximum < 1 || maximum > 4096)
      throw new IllegalArgumentException("Bounded inventory page required");
    var result = new ArrayList<Row>();
    transaction(
        token,
        c -> {
          staging(c, id);
          try (var q =
              c.prepareStatement(
                  "SELECT * FROM build_inventory_references WHERE generation=? AND role=? AND"
                      + " ordinal>? ORDER BY ordinal LIMIT ?")) {
            q.setString(1, id);
            q.setString(2, role);
            q.setLong(3, after);
            q.setInt(4, maximum);
            try (var r = q.executeQuery()) {
              while (r.next()) {
                token.throwIfCancellationRequested();
                long n = r.getLong("declared_bytes");
                var size = r.wasNull() ? OptionalLong.empty() : OptionalLong.of(n);
                result.add(
                    new Row(
                        new Reference(
                            role,
                            r.getLong("ordinal"),
                            r.getLong("block_ordinal"),
                            r.getString("key_ref"),
                            size,
                            r.getString("raw_fact")),
                        Optional.ofNullable(r.getString("resolution"))));
              }
            }
          }
        });
    return List.copyOf(result);
  }

  record Resolved(Reference reference, Resolution resolution) {}

  void resolve(String id, Reference ref, Resolution resolution, CancellationToken token) {
    resolveBatch(id, List.of(new Resolved(ref, resolution)), token);
  }

  void resolveBatch(String id, List<Resolved> values, CancellationToken token) {
    values = List.copyOf(values);
    if (values.size() > 4096)
      throw new IllegalArgumentException("Bounded resolution batch required");
    var batch = values;
    transaction(
        token,
        c -> {
          staging(c, id);
          try (var q =
              c.prepareStatement(
                  "UPDATE build_inventory_references SET"
                      + " resolution=?,ckey=?,ekey=?,decoded_bytes=?,encoded_bytes=? WHERE"
                      + " generation=? AND role=? AND ordinal=? AND resolution IS NULL")) {
            for (var value : batch) {
              token.throwIfCancellationRequested();
              var ref = value.reference();
              var resolution = value.resolution();
              q.setString(
                  1,
                  resolution.outcome()
                              == io.vibeminer.application.inventory.ManifestReferenceResolver
                                  .Outcome.RESOLVED
                          && resolution.encodedBytes().isPresent()
                          && resolution.encodedBytes().getAsLong() == 0
                      ? InventoryException.Code.UNSUPPORTED_ZERO_LENGTH_ENCODING.name()
                      : resolution.outcome().name());
              q.setString(2, resolution.contentKey().orElse(null));
              q.setString(3, resolution.encodedKey().orElse(null));
              optional(q, 4, resolution.decodedBytes());
              optional(q, 5, resolution.encodedBytes());
              q.setString(6, id);
              q.setString(7, ref.role());
              q.setLong(8, ref.ordinal());
              if (q.executeUpdate() != 1)
                throw new IllegalStateException("Missing or already resolved membership");
            }
          }
        });
  }

  static void requireContext(
      Connection c,
      String id,
      String snapshot,
      String admission,
      String enArtifact,
      String enGeneration,
      String scope,
      List<Source> sources)
      throws SQLException {
    try (var q = c.prepareStatement("SELECT * FROM build_inventory_generations WHERE id=?")) {
      q.setString(1, id);
      try (var r = q.executeQuery()) {
        if (!r.next()
            || !snapshot.equals(r.getString("snapshot_id"))
            || !admission.equals(r.getString("admission_id"))
            || !enArtifact.equals(r.getString("en_artifact"))
            || !enGeneration.equals(r.getString("en_generation"))
            || !scope.equals(r.getString("scope")))
          throw new IllegalStateException("Inventory source context changed");
      }
    }
    var expected = new HashMap<String, byte[]>();
    for (var source : sources) expected.put(source.role(), source.proof());
    try (var q =
        c.prepareStatement(
            "SELECT role,length(proof),proof FROM build_inventory_sources WHERE generation=? LIMIT"
                + " 5")) {
      q.setString(1, id);
      int count = 0;
      try (var r = q.executeQuery()) {
        while (r.next()) {
          if (r.getLong(2) > 262144 || !Arrays.equals(expected.get(r.getString(1)), r.getBytes(3)))
            throw new IllegalStateException("Inventory role proof changed");
          count++;
        }
      }
      if (count != sources.size())
        throw new IllegalStateException("Inventory required source set changed");
    }
  }

  long seal(String id, Authority authority, CancellationToken token) {
    long[] result = {0};
    transaction(
        token,
        c -> {
          staging(c, id);
          authority.validate(c);
          token.throwIfCancellationRequested();
          try (var q =
              c.prepareStatement(
                  "SELECT count(*) FROM build_inventory_sources WHERE generation=? AND (complete<>1"
                      + " OR expected_rows IS NULL)")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              r.next();
              if (r.getLong(1) != 0)
                throw new IllegalStateException("Unfinished required inventory source");
            }
          }
          try (var q =
              c.prepareStatement(
                  "SELECT resolution,encoded_bytes FROM build_inventory_references WHERE"
                      + " generation=? AND (resolution IS NULL OR resolution<>'RESOLVED' OR ckey IS"
                      + " NULL OR ekey IS NULL OR decoded_bytes IS NULL OR encoded_bytes IS NULL OR"
                      + " encoded_bytes<=0) LIMIT 1")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              if (r.next()) {
                if (InventoryException.Code.UNSUPPORTED_ZERO_LENGTH_ENCODING
                        .name()
                        .equals(r.getString(1))
                    || r.getLong(2) == 0 && !r.wasNull())
                  throw new InventoryException(
                      InventoryException.Code.UNSUPPORTED_ZERO_LENGTH_ENCODING,
                      "Known zero encoded size is unsupported by current BLTE retention");
                throw new IllegalStateException(
                    "Unresolved inventory expectation: " + r.getString(1));
              }
            }
          }
          try (var q =
              c.prepareStatement(
                  "SELECT ckey FROM build_inventory_references WHERE generation=? GROUP BY ckey"
                      + " HAVING count(DISTINCT ekey)>1 OR min(encoded_bytes)<>max(encoded_bytes)"
                      + " OR min(decoded_bytes)<>max(decoded_bytes) LIMIT 1")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              if (r.next())
                throw new IllegalStateException("Inconsistent canonical inventory target");
            }
          }
          try (var q =
              c.prepareStatement(
                  "INSERT INTO"
                      + " build_inventory_members(generation,ordinal,ckey,ekey,decoded_bytes,encoded_bytes)"
                      + " SELECT ?,row_number() OVER(ORDER BY"
                      + " ckey)-1,ckey,min(ekey),min(decoded_bytes),min(encoded_bytes) FROM"
                      + " build_inventory_references WHERE generation=? GROUP BY ckey")) {
            q.setString(1, id);
            q.setString(2, id);
            q.executeUpdate();
          }
          try (var q =
              c.prepareStatement(
                  "SELECT count(*) FROM build_inventory_members WHERE generation=?")) {
            q.setString(1, id);
            try (var r = q.executeQuery()) {
              r.next();
              result[0] = r.getLong(1);
            }
          }
          try (var q =
              c.prepareStatement(
                  "UPDATE build_inventory_generations SET status='READY',member_count=? WHERE"
                      + " id=?")) {
            q.setLong(1, result[0]);
            q.setString(2, id);
            q.executeUpdate();
          }
        });
    return result[0];
  }

  private static void quota(Connection c, long rows, long bytes, Limits limits)
      throws SQLException {
    try (var q = c.createStatement();
        var r =
            q.executeQuery(
                "SELECT coalesce(sum(row_count),0),coalesce(sum(derived_bytes),0) FROM"
                    + " build_inventory_generations")) {
      r.next();
      if (rows > limits.maxRows() - r.getLong(1) || bytes > limits.maxDerivedBytes() - r.getLong(2))
        throw new IllegalStateException("Table-global inventory quota exceeded");
    }
  }

  private static void optional(PreparedStatement q, int at, OptionalLong n) throws SQLException {
    if (n.isPresent()) q.setLong(at, n.getAsLong());
    else q.setNull(at, Types.BIGINT);
  }

  private static void staging(Connection c, String id) throws SQLException {
    try (var q = c.prepareStatement("SELECT status FROM build_inventory_generations WHERE id=?")) {
      q.setString(1, id);
      try (var r = q.executeQuery()) {
        if (!r.next() || !r.getString(1).equals("STAGING"))
          throw new IllegalStateException("No mutable inventory generation");
      }
    }
  }

  private void transaction(CancellationToken token, Authority action) {
    token.throwIfCancellationRequested();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      boolean installed = false;
      try {
        org.sqlite.ProgressHandler.setHandler(
            c,
            1000,
            new org.sqlite.ProgressHandler() {
              @Override
              protected int progress() {
                return token.isCancellationRequested() ? 1 : 0;
              }
            });
        installed = true;
        action.validate(c);
        token.throwIfCancellationRequested();
        org.sqlite.ProgressHandler.clearHandler(c);
        installed = false;
        // Commit wins a later cancellation; cancellation cannot interrupt this publication
        // boundary.
        c.commit();
      } catch (Exception failure) {
        if (installed)
          try {
            org.sqlite.ProgressHandler.clearHandler(c);
          } catch (SQLException cleanup) {
            failure.addSuppressed(cleanup);
          }
        try {
          c.rollback();
        } catch (SQLException rollback) {
          failure.addSuppressed(rollback);
        }
        if (failure instanceof SQLException) token.throwIfCancellationRequested();
        throw failure;
      }
    } catch (SQLException failure) {
      token.throwIfCancellationRequested();
      throw new IllegalStateException("Inventory storage unavailable", failure);
    }
  }
}
