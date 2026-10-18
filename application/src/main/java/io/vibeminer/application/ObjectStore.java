package io.vibeminer.application;

import io.vibeminer.domain.ExpectedObjectChecksum;
import io.vibeminer.domain.ObjectDigest;
import io.vibeminer.domain.StoredObject;
import java.io.InputStream;
import java.util.Optional;

/**
 * Blocking byte-storage port. Invoke outside the UI thread; callers own input streams.
 * Implementations validate declared length and the selected verification contract, compute physical
 * SHA-256, and expose only completely published bytes. No membership or CDN interpretation is
 * implied.
 */
public interface ObjectStore extends AutoCloseable {
  StoredObject publish(
      InputStream source,
      ExpectedObjectChecksum expected,
      long maximumBytes,
      CancellationToken cancellation);

  /**
   * Computes physical SHA-256 without a known whole-encoded checksum. Reads exact length through
   * EOF, invokes completion verification once, then checks cancellation before force/promotion.
   * Caller owns the stream and its inline protocol verifier; failure leaves no published object.
   */
  StoredObject publishVerified(
      InputStream encodedSource,
      long expectedEncodedLength,
      long maximumBytes,
      CompletionVerification completion,
      CancellationToken cancellation);

  /** Rehashes stored bytes; absent and corrupt objects have distinct outcomes. */
  Optional<StoredObject> verify(ObjectDigest digest, CancellationToken cancellation);

  /** Verifies before exposing bytes and keeps the store lease until explicitly closed. */
  ObjectReadLease openRead(ObjectDigest digest, CancellationToken cancellation);

  /** Marks an actual producer before admission or network work; legacy stores may ignore it. */
  default void publisherStarted() {}

  /** Fails BUSY rather than releasing single-process ownership while operations/readers remain. */
  @Override
  void close();
}
