package io.vibeminer.infrastructure.testing;

import io.vibeminer.application.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Separates fixture CAS setup from the opaque unused session that observes producer admission. */
public final class PublisherTrackingStore implements ObjectStore {
  public final AtomicInteger starts = new AtomicInteger();
  private final ObjectStore bytes;
  private final FileObjectStore fence;

  public PublisherTrackingStore(ObjectStore bytes, FileObjectStore fence) {
    this.bytes = bytes;
    this.fence = fence;
  }

  public void publisherStarted() {
    fence.publisherStarted();
    starts.incrementAndGet();
  }

  public StoredObject publish(
      InputStream s, ExpectedObjectChecksum e, long m, CancellationToken c) {
    return bytes.publish(s, e, m, c);
  }

  public StoredObject publishVerified(
      InputStream s, long l, long m, CompletionVerification v, CancellationToken c) {
    return bytes.publishVerified(s, l, m, v, c);
  }

  public Optional<StoredObject> verify(ObjectDigest d, CancellationToken c) {
    return bytes.verify(d, c);
  }

  public ObjectReadLease openRead(ObjectDigest d, CancellationToken c) {
    return bytes.openRead(d, c);
  }

  public void close() {}
}
