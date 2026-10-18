package io.vibeminer.infrastructure.storage;

import static io.vibeminer.application.ObjectStoreException.Code.*;

import io.vibeminer.application.ObjectStoreException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-publication staging admission, serialized by the owning store's existing coordinator.
 * Outstanding reservations remain their FULL expected lengths even after bytes have been written;
 * usable space already reflects those writes, so this deliberately conservative policy can refuse
 * work twice-accounted against the observation. It is neither an OS reservation nor whole-build
 * capacity admission. External filesystem pressure can still cause I/O failure after a check.
 */
final class StagingCapacityGuard {
  static final long DEFAULT_HEADROOM_BYTES = 512L << 20;
  private final LongSupplier usableBytes;
  private final long headroom;
  private long outstanding;

  StagingCapacityGuard(LongSupplier usableBytes, long headroom) {
    this.usableBytes = Objects.requireNonNull(usableBytes);
    if (headroom < 0) throw new IllegalArgumentException("Nonnegative staging headroom required");
    this.headroom = headroom;
  }

  void reserve(long expectedBytes) {
    long next;
    try {
      next = Math.addExact(outstanding, expectedBytes);
    } catch (ArithmeticException overflow) {
      throw new ObjectStoreException(
          LIMIT_EXCEEDED, "Concurrent staging reservation overflows", overflow);
    }
    check(next);
    outstanding = next;
  }

  void recheck() {
    check(outstanding);
  }

  private void check(long reserved) {
    long required;
    try {
      required = Math.addExact(reserved, headroom);
    } catch (ArithmeticException overflow) {
      throw new ObjectStoreException(
          LIMIT_EXCEEDED, "Staging headroom calculation overflows", overflow);
    }
    long available;
    try {
      available = usableBytes.getAsLong();
    } catch (RuntimeException unavailable) {
      throw new ObjectStoreException(
          IO_FAILURE, "App-owned staging capacity is unavailable", unavailable);
    }
    if (available < 0)
      throw new ObjectStoreException(IO_FAILURE, "App-owned staging capacity is unknown");
    if (available < required)
      throw new ObjectStoreException(
          LIMIT_EXCEEDED, "Insufficient observed staging capacity with headroom");
  }

  void release(long expectedBytes) {
    if (expectedBytes < 0 || expectedBytes > outstanding)
      throw new IllegalStateException("Staging reservation ownership mismatch");
    outstanding -= expectedBytes;
  }
}
