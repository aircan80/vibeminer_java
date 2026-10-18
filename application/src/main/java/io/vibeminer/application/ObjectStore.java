package io.vibeminer.application;

import io.vibeminer.domain.ExpectedObjectChecksum;
import io.vibeminer.domain.ObjectDigest;
import io.vibeminer.domain.StoredObject;
import java.io.InputStream;
import java.util.Optional;

/**
 * Blocking byte-storage port. Invoke outside the UI thread; callers own input streams.
 * Implementations validate expected whole-stream checksum/length, compute physical SHA-256,
 * and expose only completely published bytes. No membership or CDN interpretation is implied.
 */
public interface ObjectStore extends AutoCloseable {
    StoredObject publish(InputStream source, ExpectedObjectChecksum expected, long maximumBytes, CancellationToken cancellation);
    /** Rehashes stored bytes; absent and corrupt objects have distinct outcomes. */
    Optional<StoredObject> verify(ObjectDigest digest, CancellationToken cancellation);
    /** Verifies before exposing bytes and keeps the store lease until explicitly closed. */
    ObjectReadLease openRead(ObjectDigest digest, CancellationToken cancellation);
    /** Fails BUSY rather than releasing single-process ownership while operations/readers remain. */
    @Override void close();
}
