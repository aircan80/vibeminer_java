package io.vibeminer.infrastructure.acquisition;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Single-consumer, sequential view of one exact logical child span in an official resource.
 * Construction and available() perform no acquisition. A requested read or skip can acquire one
 * slice at a time, never outside that child. Range evidence is not whole-object protocol proof. The
 * caller owns the transport/client; closing this stream cancels only its own pending request.
 */
public final class PinnedRangeInputStream extends InputStream {
  public record Limits(long maximumWireBytes, long maximumRequests, Duration timeout) {
    public Limits {
      Objects.requireNonNull(timeout);
      if (maximumWireBytes < 1
          || maximumRequests < 1
          || timeout.isNegative()
          || timeout.isZero()
          || timeout.compareTo(Duration.ofHours(1)) > 0)
        throw new IllegalArgumentException("Invalid range stream operation budget");
    }
  }

  @FunctionalInterface
  interface RangeSource {
    CompletableFuture<AcquiredRange> fetch(CdnRangeRequest request);
  }

  /** Transport observations contain no payload buffers and imply no whole-object proof. */
  public record RangeObservation(
      CdnRangeRequest request,
      URI actualSourceUri,
      Instant receivedAt,
      int responseBytes,
      String rangeSha256) {}

  private static final byte[] EMPTY = new byte[0];
  private final RangeSource source;
  private final CdnResource resource;
  private final long wholeLength, childOffset, childLength;
  private final int maximumSlice;
  private final Limits limits;
  private final BooleanSupplier cancelled;
  private final LongSupplier nanoClock;
  private final Consumer<RangeObservation> observer;
  private final long started;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean reading = new AtomicBoolean();
  private final AtomicReference<CompletableFuture<AcquiredRange>> inFlight =
      new AtomicReference<>();
  private volatile IOException failure;
  private volatile byte[] buffer = EMPTY;
  private volatile int cursor;
  private long position, wireBytes, requests;

  public PinnedRangeInputStream(
      PinnedCdnRangeTransport transport,
      CdnResource resource,
      long wholeLength,
      long childOffset,
      long childLength,
      int maximumSlice,
      Limits limits,
      BooleanSupplier cancelled) {
    this(
        transport,
        resource,
        wholeLength,
        childOffset,
        childLength,
        maximumSlice,
        limits,
        cancelled,
        observation -> {});
  }

  /** Observer runs inline once per accepted slice, before exposing bytes; failure is terminal. */
  public PinnedRangeInputStream(
      PinnedCdnRangeTransport transport,
      CdnResource resource,
      long wholeLength,
      long childOffset,
      long childLength,
      int maximumSlice,
      Limits limits,
      BooleanSupplier cancelled,
      Consumer<RangeObservation> observer) {
    this(
        Objects.requireNonNull(transport)::fetch,
        resource,
        wholeLength,
        childOffset,
        childLength,
        maximumSlice,
        limits,
        cancelled,
        System::nanoTime,
        observer);
  }

  PinnedRangeInputStream(
      RangeSource source,
      CdnResource resource,
      long wholeLength,
      long childOffset,
      long childLength,
      int maximumSlice,
      Limits limits,
      BooleanSupplier cancelled,
      LongSupplier nanoClock) {
    this(
        source,
        resource,
        wholeLength,
        childOffset,
        childLength,
        maximumSlice,
        limits,
        cancelled,
        nanoClock,
        observation -> {});
  }

  private PinnedRangeInputStream(
      RangeSource source,
      CdnResource resource,
      long wholeLength,
      long childOffset,
      long childLength,
      int maximumSlice,
      Limits limits,
      BooleanSupplier cancelled,
      LongSupplier nanoClock,
      Consumer<RangeObservation> observer) {
    this.observer = Objects.requireNonNull(observer);
    this.source = Objects.requireNonNull(source);
    this.resource = Objects.requireNonNull(resource);
    this.limits = Objects.requireNonNull(limits);
    this.cancelled = Objects.requireNonNull(cancelled);
    this.nanoClock = Objects.requireNonNull(nanoClock);
    if (wholeLength < 1
        || childOffset < 0
        || childLength < 0
        || maximumSlice < 1
        || maximumSlice > 4 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid exact child span or bounded slice");
    try {
      if (Math.addExact(childOffset, childLength) > wholeLength)
        throw new IllegalArgumentException("Child span exceeds pinned resource");
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Child span arithmetic overflow", overflow);
    }
    this.wholeLength = wholeLength;
    this.childOffset = childOffset;
    this.childLength = childLength;
    this.maximumSlice = maximumSlice;
    this.started = nanoClock.getAsLong();
  }

  @Override
  public int read() throws IOException {
    beginRead();
    try {
      check();
      if (!fill()) return -1;
      position++;
      return buffer[cursor++] & 255;
    } finally {
      reading.set(false);
    }
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, bytes.length);
    beginRead();
    try {
      check();
      if (length == 0) return 0;
      if (!fill()) return -1;
      int count = Math.min(length, buffer.length - cursor);
      System.arraycopy(buffer, cursor, bytes, offset, count);
      cursor += count;
      position += count;
      return count;
    } finally {
      reading.set(false);
    }
  }

  @Override
  public long skip(long count) throws IOException {
    beginRead();
    try {
      check();
      if (count <= 0) return 0;
      long remaining = Math.min(count, childLength - position), skipped = 0;
      while (remaining > 0 && fill()) {
        int step = (int) Math.min(remaining, buffer.length - cursor);
        cursor += step;
        position += step;
        remaining -= step;
        skipped += step;
      }
      return skipped;
    } finally {
      reading.set(false);
    }
  }

  @Override
  public int available() throws IOException {
    check();
    return Math.max(0, buffer.length - cursor);
  }

  @Override
  public void close() {
    closed.set(true);
    var pending = inFlight.get();
    if (pending != null) pending.cancel(true);
  }

  private void beginRead() throws IOException {
    if (!reading.compareAndSet(false, true))
      throw new IOException("Range stream supports one reader");
  }

  private void check() throws IOException {
    if (failure != null) throw failure;
    if (closed.get()) throw fail(new IOException("Range stream is closed"));
    if (cancelled.getAsBoolean())
      throw fail(new InterruptedIOException("Range stream operation cancelled"));
    if (nanoClock.getAsLong() - started >= limits.timeout().toNanos())
      throw fail(new IOException("Range stream operation deadline exceeded"));
  }

  private boolean fill() throws IOException {
    check();
    if (cursor < buffer.length) return true;
    if (position == childLength) return false;
    int count = (int) Math.min(maximumSlice, childLength - position);
    if (requests >= limits.maximumRequests() || count > limits.maximumWireBytes() - wireBytes)
      throw fail(new IOException("Range stream request or response-byte budget exceeded"));
    var request =
        new CdnRangeRequest(
            resource, wholeLength, Math.addExact(childOffset, position), count, maximumSlice);
    requests++;
    wireBytes += count; // Reserve before sending, including requests that subsequently fail.
    buffer = EMPTY;
    cursor = 0;
    CompletableFuture<AcquiredRange> pending;
    try {
      pending = Objects.requireNonNull(source.fetch(request));
    } catch (RuntimeException error) {
      throw fail(new IOException("Pinned range request failed", error));
    }
    inFlight.set(pending);
    try {
      check(); // Completes the close/cancel handshake even if close preceded future registration.
      AcquiredRange response;
      while (true) {
        check();
        long remaining = limits.timeout().toNanos() - (nanoClock.getAsLong() - started);
        if (remaining <= 0) {
          check();
          throw fail(new IOException("Range stream operation deadline exceeded"));
        }
        try {
          response =
              pending.get(
                  Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
          break;
        } catch (TimeoutException wait) {
          /* Recheck operation cancellation/deadline before waiting again. */
        }
      }
      check();
      if (!request.equals(response.request()) || !request.uri().equals(response.actualSourceUri()))
        throw fail(
            new IOException(
                "Range receipt differs from pinned resource, whole size or child coordinates"));
      byte[] bytes = response.bytes();
      if (bytes.length != count)
        throw fail(new IOException("Range receipt is truncated or oversized"));
      observer.accept(
          new RangeObservation(
              request,
              response.actualSourceUri(),
              response.receivedAt(),
              bytes.length,
              response.rangeSha256()));
      check();
      buffer = bytes;
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      var error = new InterruptedIOException("Range stream reader interrupted");
      error.initCause(interrupted);
      throw fail(error);
    } catch (ExecutionException | CancellationException error) {
      Throwable cause =
          error instanceof ExecutionException && error.getCause() != null
              ? error.getCause()
              : error;
      throw fail(new IOException("Pinned range acquisition failed", cause));
    } catch (RuntimeException malformedResponse) {
      throw fail(new IOException("Invalid pinned range response", malformedResponse));
    } finally {
      inFlight.compareAndSet(pending, null);
    }
  }

  private IOException fail(IOException error) {
    if (failure == null) failure = error;
    var pending = inFlight.get();
    if (pending != null) pending.cancel(true);
    return failure;
  }
}
