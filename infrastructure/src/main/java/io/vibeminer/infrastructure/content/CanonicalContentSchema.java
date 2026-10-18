package io.vibeminer.infrastructure.content;

import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Additive bounded legacy inventory. Caller owns one migration transaction. */
public final class CanonicalContentSchema {
  private static final int MAX_LEGACY_REFERENCES = 4096;

  private CanonicalContentSchema() {}

  public static void migrate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS canonical_legacy_conflicts(namespace TEXT NOT"
              + " NULL,content_key TEXT NOT NULL,proof BLOB NOT NULL"
              + " CHECK(length(proof)<=65536),PRIMARY KEY(namespace,content_key,proof))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS canonical_legacy_blocks(product_id TEXT PRIMARY KEY,reason"
              + " TEXT NOT NULL)");
    }
    boolean hasWorkerNonce = false;
    try (var rows =
        connection.createStatement().executeQuery("PRAGMA table_info(canonical_content_slots)")) {
      while (rows.next()) if (rows.getString("name").equals("worker_nonce")) hasWorkerNonce = true;
    }
    if (!hasWorkerNonce)
      try (var statement = connection.createStatement()) {
        statement.execute(
            "ALTER TABLE canonical_content_slots ADD COLUMN worker_nonce TEXT CHECK(worker_nonce IS"
                + " NULL OR (typeof(worker_nonce)='text' AND length(worker_nonce)=36))");
      }
    var groups = new TreeMap<String, List<MetadataContentProof>>();
    try (var query =
        connection.prepareStatement(
            "SELECT"
                + " s.product_id,m.role,a.namespace,a.source_key,a.verifier_semantics,a.source_url,a.verified_at,o.digest,o.length"
                + " FROM build_object_memberships m LEFT JOIN snapshots s ON s.id=m.snapshot_id"
                + " LEFT JOIN source_aliases a ON a.namespace=m.namespace AND"
                + " a.source_key=m.source_key AND a.verifier_semantics=m.verifier_semantics LEFT"
                + " JOIN catalog_objects o ON o.digest=a.object_digest WHERE m.role IN"
                + " ('ROOT_MANIFEST','INSTALL_MANIFEST') ORDER BY s.id,m.role LIMIT ?")) {
      query.setInt(1, MAX_LEGACY_REFERENCES + 1);
      try (var rows = query.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          if (++count > MAX_LEGACY_REFERENCES)
            throw new IllegalArgumentException("Legacy metadata reconciliation exceeds bound");
          var product = rows.getString("product_id");
          if (product == null
              || rows.getString("namespace") == null
              || rows.getString("digest") == null)
            throw new IllegalArgumentException("Legacy metadata reference is incomplete");
          var namespace = rows.getString("namespace");
          var semantics = rows.getString("verifier_semantics");
          boolean root = rows.getString("role").equals("ROOT_MANIFEST");
          boolean recognized =
              root
                  ? namespace.equals("wow.root.tsfm.v1v2")
                      && semantics.equals(
                          "blte-nz-ekey-chunks-full-ckey+tsfm-v1v2-structural-eof-r1")
                  : namespace.equals("tact.install.v1")
                      && semantics.equals(
                          "blte-nz-ekey-header-or-whole-chunk-md5-ckey-md5+in-v1-r1");
          if (!product.equals("wow") || !recognized) {
            block(connection, product, "Unreviewed legacy metadata namespace or verifier");
            continue;
          }
          var identity =
              new ResolvedSourceIdentity(namespace, rows.getString("source_key"), semantics);
          var tuple = identity.immutableSourceKey().split(";", -1);
          if (tuple.length != 4)
            throw new IllegalArgumentException("Malformed legacy metadata tuple");
          var alias =
              new VerifiedSourceAlias(
                  identity,
                  new StoredObject(
                      new ObjectDigest(rows.getString("digest")), rows.getLong("length")),
                  new SourceVerificationEvidence(
                      semantics,
                      rows.getString("source_url"),
                      Instant.parse(rows.getString("verified_at"))));
          var proof =
              new MetadataContentProof(
                  alias,
                  tuple[1],
                  tuple[0],
                  OptionalLong.of(Long.parseLong(tuple[2])),
                  Optional.empty(),
                  Optional.empty());
          groups.computeIfAbsent(proof.contentKey(), ignored -> new ArrayList<>()).add(proof);
        }
      }
    }
    for (var entry : groups.entrySet())
      reconcile(connection, new ContentSlotKey("wow", entry.getKey()), entry.getValue());
  }

  private static void reconcile(
      Connection connection, ContentSlotKey slot, List<MetadataContentProof> metadata)
      throws SQLException {
    var proofs = new ArrayList<CanonicalContentProof>(metadata);
    var perEncoding = new HashMap<String, StoredObject>();
    var decodedLengths = new HashSet<Long>();
    for (var proof : metadata)
      if (proof.declaredDecodedBytes().isPresent())
        decodedLengths.add(proof.declaredDecodedBytes().getAsLong());
    if (decodedLengths.size() > 1)
      throw new IllegalArgumentException("Legacy same-content decoded declarations conflict");
    for (var proof : metadata) {
      var previous = perEncoding.putIfAbsent(proof.actualEncodingKey(), proof.object());
      if (previous != null && !previous.equals(proof.object()))
        throw new IllegalArgumentException("Legacy same-encoding physical facts conflict");
    }
    try (var query =
        connection.prepareStatement(
            "SELECT encoding_key,encoded_bytes,length(representation),representation FROM"
                + " canonical_content_slots WHERE namespace=? AND content_key=?")) {
      query.setString(1, slot.namespace());
      query.setString(2, slot.contentKey());
      try (var rows = query.executeQuery()) {
        if (rows.next()) {
          if (rows.getLong(3) > CanonicalContentRecords.MAX_BYTES)
            throw new IllegalArgumentException("Oversized legacy canonical proof");
          var bytes = rows.getBytes(4);
          if (bytes != null) proofs.add(CanonicalContentRecords.decode(bytes));
          for (var proof : metadata) {
            if (proof.actualEncodingKey().equals(rows.getString(1))
                && proof.encodedBytes() != rows.getLong(2))
              throw new IllegalArgumentException("Legacy same-encoding size conflicts");
            if (!proof.actualEncodingKey().equals(rows.getString(1))) {
              for (var candidate : proofs) conflict(connection, slot, candidate);
              return;
            }
          }
          if (bytes == null) return; // Preserve uncertain existing ownership, never replace it.
          validateSameRepresentation(proofs);
          return;
        }
      }
    }
    var keys = new HashSet<String>();
    for (var proof : proofs) keys.add(proof.actualEncodingKey());
    if (keys.size() > 1) {
      for (var proof : proofs) conflict(connection, slot, proof);
      return;
    }
    validateSameRepresentation(proofs);
    var original = metadata.getFirst();
    try (var query =
        connection.prepareStatement(
            "INSERT INTO"
                + " canonical_content_slots(namespace,content_key,encoding_key,encoded_bytes,token,status,owner_session,representation)"
                + " VALUES(?,?,?,?,?,?,?,?)")) {
      query.setString(1, slot.namespace());
      query.setString(2, slot.contentKey());
      query.setString(3, original.actualEncodingKey());
      query.setLong(4, original.encodedBytes());
      query.setString(5, UUID.randomUUID().toString());
      query.setString(6, "RETAINED");
      query.setString(7, new UUID(0, 0).toString());
      query.setBytes(8, CanonicalContentRecords.encode(original));
      query.executeUpdate();
    }
  }

  private static void validateSameRepresentation(List<CanonicalContentProof> proofs) {
    var first = proofs.getFirst();
    for (var proof : proofs)
      if (!proof.actualEncodingKey().equals(first.actualEncodingKey())
          || !proof.object().equals(first.object()))
        throw new IllegalArgumentException("Legacy same-encoding physical facts conflict");
  }

  private static void conflict(
      Connection connection, ContentSlotKey slot, CanonicalContentProof proof) throws SQLException {
    try (var query =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO canonical_legacy_conflicts VALUES(?,?,?)")) {
      query.setString(1, slot.namespace());
      query.setString(2, slot.contentKey());
      query.setBytes(3, CanonicalContentRecords.encode(proof));
      query.executeUpdate();
    }
  }

  private static void block(Connection connection, String product, String reason)
      throws SQLException {
    try (var query =
        connection.prepareStatement("INSERT OR IGNORE INTO canonical_legacy_blocks VALUES(?,?)")) {
      query.setString(1, product);
      query.setString(2, reason);
      query.executeUpdate();
    }
  }
}
