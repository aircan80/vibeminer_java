package io.vibeminer.infrastructure.encoding;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Package-private SQL engine; its caller owns complete retained-source proof and context authority.
 */
final class EncodingLookupStaging {
  record Limits(
      long maxRows,
      long maxCandidates,
      long maxDerivedBytes,
      int batchRows,
      long maxBatches,
      int maxQueryKeys,
      int maxQueryCandidates) {
    Limits {
      if (maxRows < 0
          || maxCandidates < 0
          || maxDerivedBytes < 1
          || batchRows < 1
          || batchRows > 4096
          || maxBatches < 1
          || maxQueryKeys < 1
          || maxQueryKeys > 1024
          || maxQueryCandidates < 1) throw new IllegalArgumentException("Invalid EN index bounds");
    }
  }

  record Generation(String artifact, String generation) {}

  record Candidate(
      String encodingKey, OptionalLong encodedBytes, OptionalLong specificationOrdinal) {}

  record Content(String contentKey, OptionalLong decodedBytes, List<Candidate> candidates) {
    Content {
      candidates = List.copyOf(candidates);
    }
  }

  private final SqliteCatalog catalog;

  EncodingLookupStaging(SqliteCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  Generation build(
      String artifact,
      byte[] proof,
      String owner,
      InputStream decoded,
      FullEncodingInventory.Limits parser,
      Limits limits,
      Runnable completeSource,
      CancellationToken token)
      throws IOException {
    Objects.requireNonNull(artifact);
    Objects.requireNonNull(proof);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(decoded);
    Objects.requireNonNull(parser);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(completeSource);
    Objects.requireNonNull(token);
    if (artifact.isBlank()
        || artifact.length() > 128
        || owner.isBlank()
        || owner.length() > 128
        || proof.length < 1
        || proof.length > 262144)
      throw new IllegalArgumentException("Invalid EN index identity/proof bounds");
    token.throwIfCancellationRequested();
    var g = new Generation(artifact, UUID.randomUUID().toString());
    boolean created = false, ready = false;
    Throwable primary = null;
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try (var q =
          c.prepareStatement(
              "INSERT INTO en_lookup_artifacts(artifact,generation,owner,status,source_proof)"
                  + " VALUES(?,?,?,'STAGING',?)")) {
        q.setString(1, artifact);
        q.setString(2, g.generation());
        q.setString(3, owner);
        q.setBytes(4, proof);
        q.executeUpdate();
      }
      c.setAutoCommit(true);
      created = true;
      try (var sink = new Sink(c, g, owner, limits, token)) {
        var done =
            new FullEncodingInventory().read(decoded, parser, sink, token::isCancellationRequested);
        sink.flush();
        token.throwIfCancellationRequested();
        completeSource.run();
        token.throwIfCancellationRequested();
        if (done.contentRecords() != sink.contents
            || done.encodingRecords() != sink.encodings
            || done.candidateReferences() != sink.candidates)
          throw new IllegalStateException("EN completion counters changed");
        c.setAutoCommit(false);
        try (var q =
            c.prepareStatement(
                "UPDATE en_lookup_artifacts SET"
                    + " status='READY',content_count=?,encoding_count=?,candidate_count=? WHERE"
                    + " artifact=? AND generation=? AND owner=? AND status='STAGING'")) {
          q.setLong(1, sink.contents);
          q.setLong(2, sink.encodings);
          q.setLong(3, sink.candidates);
          q.setString(4, artifact);
          q.setString(5, g.generation());
          q.setString(6, owner);
          if (q.executeUpdate() != 1) throw new IllegalStateException("EN staging owner changed");
        }
        token.throwIfCancellationRequested();
        c.commit();
        ready = true;
        return g;
      } catch (FullEncodingInventoryException failed) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          failed.addSuppressed(rollback);
        }
        throw new IOException("Invalid complete EN grammar", failed);
      } catch (RuntimeException | Error failed) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          failed.addSuppressed(rollback);
        }
        throw failed;
      }
    } catch (SQLException failed) {
      var mapped = new IllegalStateException("EN index storage failure", failed);
      primary = mapped;
      throw mapped;
    } catch (IOException | RuntimeException | Error failed) {
      primary = failed;
      throw failed;
    } finally {
      if (created && !ready)
        try (var c = catalog.connect();
            var q =
                c.prepareStatement(
                    "DELETE FROM en_lookup_artifacts WHERE artifact=? AND generation=? AND owner=?"
                        + " AND status='STAGING'")) {
          q.setString(1, artifact);
          q.setString(2, g.generation());
          q.setString(3, owner);
          q.executeUpdate();
        } catch (SQLException failed) {
          var cleanup = new IllegalStateException("Cannot retire EN staging generation", failed);
          if (primary != null) primary.addSuppressed(cleanup);
          else throw cleanup;
        }
    }
  }

  List<Content> lookup(
      Generation generation, List<String> keys, Limits limits, CancellationToken token) {
    Objects.requireNonNull(generation);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(token);
    keys = List.copyOf(keys);
    if (keys.size() > limits.maxQueryKeys())
      throw new IllegalArgumentException("EN query key bound exceeded");
    for (var key : keys)
      if (!key.matches("[0-9a-f]{32}"))
        throw new IllegalArgumentException("Expected canonical CKey");
    token.throwIfCancellationRequested();
    try (var c = catalog.connect()) {
      c.setAutoCommit(false);
      try (var q =
          c.prepareStatement(
              "SELECT status FROM en_lookup_artifacts WHERE artifact=? AND generation=?")) {
        bind(q, generation);
        try (var r = q.executeQuery()) {
          if (!r.next() || !r.getString(1).equals("READY"))
            throw new IllegalStateException("EN generation is not ready");
        }
      }
      var result = new ArrayList<Content>();
      int total = 0;
      try (var content =
              c.prepareStatement(
                  "SELECT decoded_bytes,candidate_count FROM en_lookup_content WHERE artifact=? AND"
                      + " generation=? AND ckey=?");
          var candidates =
              c.prepareStatement(
                  "SELECT a.ordinal,a.ekey,b.encoded_bytes,b.spec_ordinal FROM en_lookup_candidates"
                      + " a LEFT JOIN en_lookup_encoding b ON a.artifact=b.artifact AND"
                      + " a.generation=b.generation AND a.ekey=b.ekey WHERE a.artifact=? AND"
                      + " a.generation=? AND a.ckey=? ORDER BY a.ordinal")) {
        for (var key : keys) {
          token.throwIfCancellationRequested();
          bind(content, generation);
          content.setString(3, key);
          try (var r = content.executeQuery()) {
            if (!r.next()) {
              result.add(new Content(key, OptionalLong.empty(), List.of()));
              continue;
            }
            long size = r.getLong(1);
            int expected = r.getInt(2);
            var found = new ArrayList<Candidate>();
            bind(candidates, generation);
            candidates.setString(3, key);
            try (var rows = candidates.executeQuery()) {
              while (rows.next()) {
                token.throwIfCancellationRequested();
                if (++total > limits.maxQueryCandidates())
                  throw new IllegalArgumentException("EN query candidate bound exceeded");
                if (rows.getInt(1) != found.size())
                  throw new IllegalStateException("EN candidate ordinal gap");
                long bytes = rows.getLong(3);
                boolean missing = rows.wasNull();
                long spec = rows.getLong(4);
                if (rows.wasNull() != missing)
                  throw new IllegalStateException("Partial encoding metadata");
                found.add(
                    new Candidate(
                        rows.getString(2),
                        missing ? OptionalLong.empty() : OptionalLong.of(bytes),
                        missing ? OptionalLong.empty() : OptionalLong.of(spec)));
              }
            }
            if (found.size() != expected)
              throw new IllegalStateException("EN candidate count mismatch");
            result.add(new Content(key, OptionalLong.of(size), found));
          }
        }
      }
      token.throwIfCancellationRequested();
      c.commit();
      return List.copyOf(result);
    } catch (SQLException failed) {
      throw new IllegalStateException("Cannot query EN index", failed);
    }
  }

  Optional<Generation> findReady(String artifact, byte[] expectedProof, CancellationToken token) {
    token.throwIfCancellationRequested();
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "SELECT generation,source_proof FROM en_lookup_artifacts WHERE artifact=? AND"
                    + " status='READY' ORDER BY rowid DESC LIMIT 1")) {
      q.setString(1, artifact);
      try (var r = q.executeQuery()) {
        if (!r.next()) return Optional.empty();
        if (!Arrays.equals(expectedProof, r.getBytes(2)))
          throw new IllegalStateException("EN artifact source proof changed");
        return Optional.of(new Generation(artifact, r.getString(1)));
      }
    } catch (SQLException failed) {
      throw new IllegalStateException("Cannot inspect EN generation", failed);
    }
  }

  void requireSource(Generation generation, byte[] expectedProof, CancellationToken token) {
    token.throwIfCancellationRequested();
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "SELECT source_proof FROM en_lookup_artifacts WHERE artifact=? AND generation=? AND"
                    + " status='READY'")) {
      bind(q, generation);
      try (var r = q.executeQuery()) {
        if (!r.next() || !Arrays.equals(expectedProof, r.getBytes(1)))
          throw new IllegalStateException("EN generation/source proof is stale");
      }
    } catch (SQLException failed) {
      throw new IllegalStateException("Cannot validate EN generation", failed);
    }
  }

  /**
   * Recovery caller must have exclusive proof that this owner has retired; never infer from time.
   */
  void retireStagingOwner(String retiredOwner, CancellationToken token) {
    Objects.requireNonNull(retiredOwner);
    token.throwIfCancellationRequested();
    try (var c = catalog.connect();
        var q =
            c.prepareStatement(
                "DELETE FROM en_lookup_artifacts WHERE owner=? AND status='STAGING'")) {
      q.setString(1, retiredOwner);
      q.executeUpdate();
    } catch (SQLException failed) {
      throw new IllegalStateException("Cannot retire inactive EN index owner", failed);
    }
  }

  private static void bind(PreparedStatement q, Generation g) throws SQLException {
    q.setString(1, g.artifact());
    q.setString(2, g.generation());
  }

  private static final class Sink implements FullEncodingInventory.Consumer, AutoCloseable {
    final Connection connection;
    final Generation g;
    final String owner;
    final Limits limits;
    final CancellationToken token;
    final PreparedStatement content, candidate, encoding;
    long contents, encodings, candidates, bytes, batches;
    int pending;

    Sink(Connection c, Generation g, String owner, Limits limits, CancellationToken token)
        throws SQLException {
      this.connection = c;
      this.g = g;
      this.owner = owner;
      this.limits = limits;
      this.token = token;
      content = c.prepareStatement("INSERT INTO en_lookup_content VALUES(?,?,?,?,?)");
      candidate = c.prepareStatement("INSERT INTO en_lookup_candidates VALUES(?,?,?,?,?)");
      encoding = c.prepareStatement("INSERT INTO en_lookup_encoding VALUES(?,?,?,?,?)");
    }

    public void header(IndexedEncodingParser.Layout layout) {}

    public void specification(
        int i,
        String
            value) {} // parser validates ordinal references; no opaque ESpec copy needed for size

    // lookup

    public void trailer(String value) {}

    public void content(int page, long ordinal, EncodingManifest.ContentEntry entry) {
      token.throwIfCancellationRequested();
      if (contents + encodings >= limits.maxRows())
        throw new IllegalArgumentException("EN row bound exceeded");
      try {
        charge(56);
        beginBatch();
        bind(content, g);
        content.setString(3, entry.contentKey());
        content.setLong(4, entry.decodedSize());
        content.setInt(5, entry.encodingKeys().size());
        content.executeUpdate();
        contents++;
        tick();
        int n = 0;
        for (var key : entry.encodingKeys()) {
          if (candidates >= limits.maxCandidates())
            throw new IllegalArgumentException("EN candidate bound exceeded");
          charge(68);
          beginBatch();
          bind(candidate, g);
          candidate.setString(3, entry.contentKey());
          candidate.setInt(4, n++);
          candidate.setString(5, key);
          candidate.executeUpdate();
          candidates++;
          tick();
        }
      } catch (SQLException failed) {
        throw new IllegalStateException("Cannot stage EN content", failed);
      }
    }

    public void encoding(int page, long ordinal, EncodingManifest.EncodedEntry entry) {
      token.throwIfCancellationRequested();
      if (contents + encodings >= limits.maxRows())
        throw new IllegalArgumentException("EN row bound exceeded");
      try {
        charge(56);
        beginBatch();
        bind(encoding, g);
        encoding.setString(3, entry.encodingKey());
        encoding.setLong(4, entry.encodedSize());
        encoding.setLong(5, entry.specIndex());
        encoding.executeUpdate();
        encodings++;
        tick();
      } catch (SQLException failed) {
        throw new IllegalStateException("Cannot stage EN encoding", failed);
      }
    }

    void charge(long size) {
      token.throwIfCancellationRequested();
      if (size > limits.maxDerivedBytes() - bytes)
        throw new IllegalArgumentException("EN logical derived byte bound exceeded");
      bytes += size;
    }

    void beginBatch() throws SQLException {
      token.throwIfCancellationRequested();
      if (connection.getAutoCommit()) connection.setAutoCommit(false);
    }

    void tick() throws SQLException {
      if (++pending >= limits.batchRows()) flush();
    }

    void flush() throws SQLException {
      token.throwIfCancellationRequested();
      if (pending == 0) return;
      if (batches >= limits.maxBatches())
        throw new IllegalArgumentException("EN batch bound exceeded");
      try (var q =
          connection.prepareStatement(
              "SELECT 1 FROM en_lookup_artifacts WHERE artifact=? AND generation=? AND owner=? AND"
                  + " status='STAGING'")) {
        bind(q, g);
        q.setString(3, owner);
        try (var r = q.executeQuery()) {
          if (!r.next()) throw new IllegalStateException("EN staging fence lost");
        }
      }
      connection.setAutoCommit(true);
      pending = 0;
      batches++;
    }

    public void close() throws SQLException {
      content.close();
      candidate.close();
      encoding.close();
    }
  }
}
