package io.vibeminer.application;

import io.vibeminer.domain.StoredObject;
import java.io.InputStream;

/** Protects one active read; close the lease as well as consuming/closing its stream. */
public interface ObjectReadLease extends AutoCloseable {
    StoredObject object();
    InputStream stream();
    @Override void close();
}
