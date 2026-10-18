package io.vibeminer.application.buildretention;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Small durable ledger values. None authenticate an inventory or imply current physical readiness.
 */
public final class BuildRetention {
  private BuildRetention() {}

  public static final int MAXIMUM_FILL = 128, MAXIMUM_QUEUED = 256, MAXIMUM_RUNNING = 2;

  public enum Mode {
    ON_DEMAND,
    DOWNLOAD_ALL
  }

  public enum State {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  public enum FailureCode {
    UNAVAILABLE,
    CORRUPT,
    UNSUPPORTED,
    QUOTA,
    STORAGE,
    CANCELLED,
    INTERRUPTED
  }

  public record Target(
      String groupId,
      String snapshotId,
      String admissionId,
      String inventoryId,
      long inventoryGeneration,
      String scopeRevision,
      String scopeCommitment) {
    public Target {
      digest(groupId);
      digest(snapshotId);
      digest(admissionId);
      digest(inventoryId);
      digest(scopeCommitment);
      text(scopeRevision, 128);
      if (inventoryGeneration < 1)
        throw new IllegalArgumentException("Positive inventory generation required");
    }

    public String identity() {
      try {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        for (var value :
            List.of(
                "build-retention-target-v1",
                groupId,
                snapshotId,
                admissionId,
                inventoryId,
                Long.toString(inventoryGeneration),
                scopeRevision,
                scopeCommitment)) {
          var utf8 = value.getBytes(StandardCharsets.UTF_8);
          out.writeInt(utf8.length);
          out.write(utf8);
        }
        return HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
      } catch (IOException | NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }
  }

  public record Policy(
      long maximumObjectBytes,
      long maximumWireBytes,
      long maximumAdditionalBytes,
      long maximumRequests,
      int maximumAttempts) {
    public Policy {
      if (maximumObjectBytes < 1
          || maximumWireBytes < 0
          || maximumAdditionalBytes < 0
          || maximumRequests < 0
          || maximumAttempts < 1
          || maximumAttempts > 10)
        throw new IllegalArgumentException("Bounded explicit safety policy required");
    }
  }

  /**
   * Conservative reserved ceilings for bounded locator work plus payload, never measured activity
   * or availability.
   */
  public record Cost(long wireBytes, long additionalBytes, long requests) {
    public Cost {
      if (wireBytes < 0 || additionalBytes < 0 || requests < 0)
        throw new IllegalArgumentException("Known nonnegative reserved costs required");
    }
  }

  /**
   * One resolved inventory acquisition unit; all manifest origin references remain in inventory.
   */
  public record Member(
      long ordinal,
      String memberId,
      String contentKey,
      String encodingKey,
      long encodedBytes,
      Cost cost,
      String membershipCommitment) {
    public Member {
      digest(memberId);
      key(contentKey);
      key(encodingKey);
      digest(membershipCommitment);
      Objects.requireNonNull(cost);
      if (ordinal < 0 || encodedBytes < 1)
        throw new IllegalArgumentException("Resolved exact member required");
    }
  }

  public record Seal(Target target, long memberCount, String verifierRevision) {
    public Seal {
      Objects.requireNonNull(target);
      text(verifierRevision, 128);
      if (memberCount < 0) throw new IllegalArgumentException("Complete inventory count required");
    }
  }

  public record Intent(
      Target target,
      Mode mode,
      long generation,
      Policy policy,
      Instant authorizedAt,
      long memberCount,
      long nextOrdinal,
      Cost reserved) {
    public Intent {
      Objects.requireNonNull(target);
      Objects.requireNonNull(mode);
      Objects.requireNonNull(policy);
      Objects.requireNonNull(authorizedAt);
      Objects.requireNonNull(reserved);
      if (generation < 1
          || memberCount < 0
          || nextOrdinal < 0
          || nextOrdinal > memberCount
          || reserved.wireBytes() > policy.maximumWireBytes()
          || reserved.additionalBytes() > policy.maximumAdditionalBytes()
          || reserved.requests() > policy.maximumRequests())
        throw new IllegalArgumentException("Consistent durable intent required");
    }

    public boolean enumerationComplete() {
      return nextOrdinal == memberCount;
    }
  }

  public record Failure(FailureCode code, String message) {
    public Failure {
      Objects.requireNonNull(code);
      text(message, 4096);
    }
  }

  public record Lease(
      String targetId, String memberId, long modeGeneration, UUID workerNonce, UUID storeEpoch) {
    public Lease {
      digest(targetId);
      digest(memberId);
      Objects.requireNonNull(workerNonce);
      Objects.requireNonNull(storeEpoch);
      if (modeGeneration < 1)
        throw new IllegalArgumentException("Positive mode generation required");
    }
  }

  public record Job(
      Target target,
      Member member,
      State state,
      int attempts,
      Optional<Lease> lease,
      Optional<String> fulfillmentProof,
      Optional<Failure> failure) {
    public Job {
      Objects.requireNonNull(target);
      Objects.requireNonNull(member);
      Objects.requireNonNull(state);
      Objects.requireNonNull(lease);
      Objects.requireNonNull(fulfillmentProof);
      Objects.requireNonNull(failure);
      fulfillmentProof.ifPresent(BuildRetention::digest);
      if (attempts < 0
          || attempts > 10
          || state == State.RUNNING != lease.isPresent()
          || state == State.SUCCEEDED != fulfillmentProof.isPresent()
          || state == State.FAILED && failure.isEmpty()
          || (state == State.SUCCEEDED || state == State.RUNNING) && failure.isPresent())
        throw new IllegalArgumentException("Consistent exact job state required");
      if (lease.isPresent()
          && (!lease.get().targetId().equals(target.identity())
              || !lease.get().memberId().equals(member.memberId())))
        throw new IllegalArgumentException("Lease changed job identity");
    }
  }

  /** Historical completion receipt only; Full separately rechecks current scope physical proofs. */
  public record Finish(State state, Optional<String> fulfillmentProof, Optional<Failure> failure) {
    public Finish {
      Objects.requireNonNull(state);
      Objects.requireNonNull(fulfillmentProof);
      Objects.requireNonNull(failure);
      fulfillmentProof.ifPresent(BuildRetention::digest);
      if (state != State.SUCCEEDED && state != State.FAILED
          || state == State.SUCCEEDED && (fulfillmentProof.isEmpty() || failure.isPresent())
          || state == State.FAILED && (fulfillmentProof.isPresent() || failure.isEmpty()))
        throw new IllegalArgumentException("Exact completed proof or typed failure required");
    }
  }

  private static void digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Exact SHA256 identity required");
  }

  private static void key(String value) {
    if (value == null || !value.matches("[0-9a-f]{32}") || value.equals("0".repeat(32)))
      throw new IllegalArgumentException("Exact nonzero source key required");
  }

  private static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bounded lossless text required");
  }
}
