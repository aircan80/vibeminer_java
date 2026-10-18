package io.vibeminer.infrastructure.buildretention;

import static io.vibeminer.application.buildretention.BuildRetentionException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.domain.content.EncodedContentProof;
import io.vibeminer.domain.content.MetadataContentProof;
import io.vibeminer.domain.selectedfile.ContentRepresentation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionFacts;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.inventory.SqliteBuildRetentionInventory;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.selectedfile.SelectedFileRecords;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Attaches inventory authority to an unchanged actual canonical encoding, never a fake FDID. */
public final class SqliteBuildRetentionSourceAssociations {
  private final SqliteCatalog catalog;
  private final ObjectStore store;

  public SqliteBuildRetentionSourceAssociations(SqliteCatalog catalog, ObjectStore store) {
    this.catalog = Objects.requireNonNull(catalog);
    this.store = Objects.requireNonNull(store);
  }

  public String attach(
      SqliteBuildRetentionInventory.SourceAuthority authority,
      BuildRetention.Target target,
      BuildRetention.Member member,
      ContentRepresentation actual,
      CancellationToken token) {
    return access(authority, target, member, actual, token, true).orElseThrow();
  }

  /** Read-only current association proof; never treats job status as retention evidence. */
  public Optional<String> find(
      SqliteBuildRetentionInventory.SourceAuthority authority,
      BuildRetention.Target target,
      BuildRetention.Member member,
      ContentRepresentation actual,
      CancellationToken token) {
    return access(authority, target, member, actual, token, false);
  }

  /** Own-current-admission metadata fulfills itself without claiming encoded-file capability. */
  public Optional<String> findMetadata(
      SqliteBuildRetentionInventory.SourceAuthority authority,
      BuildRetention.Target target,
      BuildRetention.Member member,
      CancellationToken token) {
    Objects.requireNonNull(token).throwIfCancellationRequested();
    if (!authority.target().equals(target) || !authority.member().equals(member))
      throw failure(CORRUPT_STATE, "Metadata fulfillment differs from sealed member");
    var matches =
        authority.metadata().metadata().stream()
            .filter(fact -> fact.slot().contentKey().equals(member.contentKey()))
            .toList();
    if (matches.isEmpty()) return Optional.empty();
    if (matches.size() != 1) throw failure(CORRUPT_STATE, "Ambiguous admitted metadata member");
    var fact = matches.getFirst();
    var expected = fact.proof();
    metadataFulfillmentProof(target, member, fact);
    try (var lease = store.openRead(expected.object().digest(), token);
        var connection = catalog.connect()) {
      if (!lease.object().equals(expected.object()))
        throw failure(CORRUPT_STATE, "Physical metadata geometry changed");
      connection.setAutoCommit(false);
      try {
        authority.validateInTransaction(connection, token);
        CanonicalContentGuards.check(connection, fact.slot());
        try (var query =
            connection.prepareStatement(
                "SELECT token,status,length(representation),representation FROM"
                    + " canonical_content_slots WHERE namespace=? AND content_key=?")) {
          query.setString(1, fact.slot().namespace());
          query.setString(2, fact.slot().contentKey());
          try (var rows = query.executeQuery()) {
            if (!rows.next() || !rows.getString(2).equals("RETAINED"))
              throw failure(NOT_READY, "Admitted metadata is not currently retained");
            if (!fact.canonicalReservationToken().toString().equals(rows.getString(1))
                || rows.getLong(3) < 1
                || rows.getLong(3) > CanonicalContentRecords.MAX_BYTES)
              throw failure(CORRUPT_STATE, "Admitted metadata canonical identity changed");
            var actual = CanonicalContentRecords.decode(rows.getBytes(4));
            if (!(actual instanceof MetadataContentProof metadata) || !metadata.equals(expected))
              throw failure(CORRUPT_STATE, "Admitted original metadata proof changed");
          }
        }
        token.throwIfCancellationRequested();
        connection.rollback();
        return Optional.of(metadataFulfillmentProof(target, member, fact));
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (SQLException failure) {
      throw new BuildRetentionException(
          STORAGE_UNAVAILABLE, "Cannot verify admitted metadata member", failure);
    }
  }

  /**
   * Pure commitment only; caller must separately establish current admission and physical proof.
   */
  public static String metadataFulfillmentProof(
      BuildRetention.Target target,
      BuildRetention.Member member,
      BuildMetadataAdmissionFacts.MetadataFact fact) {
    var proof = fact.proof();
    if (!fact.slot().equals(new ContentSlotKey("wow", member.contentKey()))
        || !proof.actualEncodingKey().equals(member.encodingKey())
        || proof.object().length() != member.encodedBytes()
        || proof.decodedAttestation().isEmpty()
        || proof.structuralAttestation().isEmpty())
      throw failure(CORRUPT_STATE, "Member is not the exact admitted metadata representation");
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (var value :
          List.of(
              "build-retention-admitted-metadata-v1",
              target.identity(),
              Long.toString(member.ordinal()),
              member.memberId(),
              member.contentKey(),
              member.encodingKey(),
              Long.toString(member.encodedBytes()),
              Long.toString(member.cost().wireBytes()),
              Long.toString(member.cost().additionalBytes()),
              Long.toString(member.cost().requests()),
              member.membershipCommitment(),
              fact.role().name(),
              fact.canonicalReservationToken().toString())) {
        var encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
      }
      var encoded = CanonicalContentRecords.encode(proof);
      out.writeInt(encoded.length);
      out.write(encoded);
      out.flush();
      return sha256(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private Optional<String> access(
      SqliteBuildRetentionInventory.SourceAuthority authority,
      BuildRetention.Target target,
      BuildRetention.Member member,
      ContentRepresentation actual,
      CancellationToken token,
      boolean create) {
    if (!authority.target().equals(target)
        || !authority.member().equals(member)
        || !actual.slot().contentKey().equals(member.contentKey())
        || !actual.slot().namespace().equals("wow")
        || actual.encoded().object().length() != member.encodedBytes()
        || !actual
            .encoded()
            .source()
            .equals(ContentRepresentation.encodedIdentity(member.encodingKey())))
      throw failure(CORRUPT_STATE, "Inventory member differs from actual encoding");
    try (var lease = store.openRead(actual.encoded().object().digest(), token);
        var connection = catalog.connect()) {
      if (!lease.object().equals(actual.encoded().object()))
        throw failure(CORRUPT_STATE, "Physical encoding changed");
      connection.setAutoCommit(false);
      try {
        authority.validateInTransaction(connection, token);
        CanonicalContentGuards.check(connection, actual.slot());
        UUID reservation;
        try (var query =
            connection.prepareStatement(
                "SELECT token,status,worker_nonce,length(representation),representation FROM"
                    + " canonical_content_slots WHERE namespace=? AND content_key=?")) {
          query.setString(1, actual.slot().namespace());
          query.setString(2, actual.slot().contentKey());
          try (var rows = query.executeQuery()) {
            if (!rows.next() || !rows.getString(2).equals("RETAINED"))
              throw failure(NOT_READY, "Canonical encoding not retained");
            if (rows.getString(3) != null)
              throw failure(BUSY, "Actual canonical source worker has not retired");
            if (rows.getLong(4) > CanonicalContentRecords.MAX_BYTES)
              throw failure(CORRUPT_STATE, "Oversized canonical proof");
            var proof = CanonicalContentRecords.decode(rows.getBytes(5));
            if (!(proof instanceof EncodedContentProof encoded)
                || !encoded.receipt().equals(actual))
              throw failure(CORRUPT_STATE, "Canonical original receipt changed");
            reservation = UUID.fromString(rows.getString(1));
          }
        }
        var record = record(target, member, reservation, actual);
        var commitment = sha256(record);
        try (var query =
            connection.prepareStatement(
                "SELECT length(record),record,fulfillment_proof FROM build_retention_sources WHERE"
                    + " target_id=? AND member_id=?")) {
          query.setString(1, target.identity());
          query.setString(2, member.memberId());
          try (var rows = query.executeQuery()) {
            if (rows.next()) {
              if (rows.getLong(1) < 1 || rows.getLong(1) > 131072)
                throw failure(CORRUPT_STATE, "Invalid inventory association record length");
              verifyRecord(
                  rows.getBytes(2), rows.getString(3), target, member, reservation, actual);
              token.throwIfCancellationRequested();
              connection.commit();
              return Optional.of(commitment);
            }
          }
        }
        if (!create) {
          token.throwIfCancellationRequested();
          connection.rollback();
          return Optional.empty();
        }
        try (var insert =
            connection.prepareStatement(
                "INSERT INTO build_retention_sources(target_id,member_id,record,fulfillment_proof)"
                    + " VALUES(?,?,?,?)")) {
          insert.setString(1, target.identity());
          insert.setString(2, member.memberId());
          insert.setBytes(3, record);
          insert.setString(4, commitment);
          insert.executeUpdate();
        }
        token.throwIfCancellationRequested();
        connection.commit();
        return Optional.of(commitment);
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    } catch (SQLException failure) {
      throw new BuildRetentionException(
          STORAGE_UNAVAILABLE, "Cannot attach retained inventory member", failure);
    }
  }

  /**
   * Pure exact row verification for an already authority-checked bounded overview page. This method
   * grants no membership or physical-readiness authority by itself.
   */
  public static void verifyRecord(
      byte[] storedRecord,
      String fulfillmentProof,
      BuildRetention.Target target,
      BuildRetention.Member member,
      UUID reservation,
      ContentRepresentation actual) {
    if (storedRecord == null
        || storedRecord.length < 1
        || storedRecord.length > 131072
        || !Arrays.equals(storedRecord, record(target, member, reservation, actual))
        || !sha256(storedRecord).equals(fulfillmentProof))
      throw failure(CORRUPT_STATE, "Immutable inventory source association conflicts");
  }

  private static byte[] record(
      BuildRetention.Target target,
      BuildRetention.Member member,
      UUID token,
      ContentRepresentation actual) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (var value :
          List.of(
              "build-retention-source-v1",
              target.groupId(),
              target.snapshotId(),
              target.admissionId(),
              target.inventoryId(),
              Long.toString(target.inventoryGeneration()),
              target.scopeRevision(),
              target.scopeCommitment(),
              Long.toString(member.ordinal()),
              member.memberId(),
              member.contentKey(),
              member.encodingKey(),
              Long.toString(member.encodedBytes()),
              Long.toString(member.cost().wireBytes()),
              Long.toString(member.cost().additionalBytes()),
              Long.toString(member.cost().requests()),
              member.membershipCommitment(),
              token.toString())) {
        var encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
      }
      var encoded = SelectedFileRecords.representation(actual);
      out.writeInt(encoded.length);
      out.write(encoded);
      out.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static BuildRetentionException failure(
      BuildRetentionException.Code code, String message) {
    return new BuildRetentionException(code, message);
  }
}
