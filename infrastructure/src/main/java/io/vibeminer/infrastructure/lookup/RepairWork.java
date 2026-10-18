package io.vibeminer.infrastructure.lookup;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.content.*;
import io.vibeminer.infrastructure.acquisition.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * One repair's independent consent ledger; no retry, stored observations or shared mutable state.
 */
final class RepairWork {
  private final MetadataRepairPlan plan;
  private final CancellationToken cancellation;
  private final LongSupplier nanos;
  private final long started;
  private final MessageDigest observations;
  private int requests;
  private long wire;
  private Instant lastReceived;

  RepairWork(MetadataRepairPlan plan, CancellationToken cancellation, LongSupplier nanos) {
    this.plan = plan;
    this.cancellation = cancellation;
    this.nanos = nanos;
    started = nanos.getAsLong();
    try {
      observations = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  void check() {
    cancellation.throwIfCancellationRequested();
    if (nanos.getAsLong() - started >= plan.cost().maximumPayloadDuration().toNanos())
      throw fail("Repair payload deadline exceeded");
  }

  boolean cancelled() {
    check();
    return false;
  }

  void decodedWork(long bytes) {
    check();
    if (bytes < 1 || bytes > plan.cost().maximumDecodedWorkBytes())
      throw fail("Repair decoded work exceeds consent");
  }

  void reserve(long bytes) {
    check();
    if (bytes < 1
        || bytes > plan.cost().encodedTransferBytes() - wire
        || requests >= plan.cost().maximumPayloadRequests())
      throw fail("Repair payload exceeds wire/request consent");
    wire += bytes;
    requests++;
  }

  <T> T await(CompletableFuture<T> future) {
    try {
      while (true) {
        check();
        try {
          T value = future.get(100, TimeUnit.MILLISECONDS);
          check();
          return value;
        } catch (TimeoutException pending) {
          // Recheck cancellation and the independent consent deadline.
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new CancellationException("Repair interrupted");
    } catch (ExecutionException failed) {
      future.cancel(true);
      throw LookupWork.failure(failed.getCause());
    } catch (RuntimeException failed) {
      future.cancel(true);
      throw failed;
    }
  }

  void observe(String url, long offset, long length, Instant received) {
    check();
    if (!url.equals(plan.sourceObservation().resourceUrl()))
      throw new MetadataRepairException(
          MetadataRepairException.Code.INTEGRITY, "Repair response route changed");
    observations.update(
        (url + "\n" + offset + "\n" + length + "\n" + received + "\n")
            .getBytes(StandardCharsets.UTF_8));
    lastReceived = received;
  }

  int requests() {
    return requests;
  }

  long wire() {
    return wire;
  }

  Instant received() {
    return lastReceived;
  }

  String completeDigest() {
    check();
    if (wire != plan.originalProof().object().length() || lastReceived == null)
      throw new MetadataRepairException(
          MetadataRepairException.Code.INTEGRITY,
          "Repair transfer did not cover exact original bytes");
    return HexFormat.of().formatHex(observations.digest());
  }

  private static MetadataRepairException fail(String message) {
    return new MetadataRepairException(MetadataRepairException.Code.LIMIT, message);
  }
}
