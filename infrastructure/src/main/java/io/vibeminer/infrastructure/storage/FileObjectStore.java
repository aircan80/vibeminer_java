package io.vibeminer.infrastructure.storage;

import static io.vibeminer.application.ObjectStoreException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.CompletionVerification;
import io.vibeminer.application.ObjectReadLease;
import io.vibeminer.application.ObjectStore;
import io.vibeminer.application.ObjectStoreException;
import io.vibeminer.domain.ExpectedObjectChecksum;
import io.vibeminer.domain.ObjectDigest;
import io.vibeminer.domain.StoredObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-owned immutable byte store. One process owns the root; operations coordinate publication and
 * leases. No build membership, eviction, automatic corruption repair or network I/O occurs.
 */
public final class FileObjectStore implements ObjectStore {
  private static final int BUFFER_BYTES = 64 * 1024;
  private final Object coordinator = new Object();
  private final Path objects;
  private final Path work;
  private final FileChannel lockChannel;
  private final FileLock processLock;
  private final StoreSession contentSession;
  private boolean closed;
  private int operations;
  private int readers;

  public FileObjectStore(Path root) {
    Objects.requireNonNull(root, "root");
    FileChannel channel = null;
    FileLock lock = null;
    try {
      var storeRoot = root.toAbsolutePath().normalize();
      ensureDirectory(storeRoot);
      channel =
          FileChannel.open(
              storeRoot.resolve(".store.lock"),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException busy) {
        throw failure(STORE_IN_USE, "The object store is already in use", busy);
      }
      if (lock == null) throw failure(STORE_IN_USE, "The object store is already in use");
      objects = storeRoot.resolve("objects");
      work = storeRoot.resolve("work");
      ensureDirectory(objects);
      ensureDirectory(work);
      try (var stages = Files.newDirectoryStream(work, "stage-*.tmp")) {
        for (var stage : stages) {
          if (Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)) Files.delete(stage);
        }
      }
      lockChannel = channel;
      processLock = lock;
      contentSession = new StoreSession();
    } catch (IOException | RuntimeException invalid) {
      if (lock != null)
        try {
          lock.release();
        } catch (IOException suppressed) {
          invalid.addSuppressed(suppressed);
        }
      if (channel != null)
        try {
          channel.close();
        } catch (IOException suppressed) {
          invalid.addSuppressed(suppressed);
        }
      if (invalid instanceof ObjectStoreException typed) throw typed;
      throw failure(IO_FAILURE, "Cannot open the app-owned object store", invalid);
    }
  }

  /** One opaque ownership epoch, minted only after this store acquired its exclusive lock. */
  public StoreSession contentSession() {
    return contentSession;
  }

  /** Startup recovery authority belongs to this exclusive store lifetime, never an adapter UUID. */
  public final class StoreSession {
    private final UUID epoch = UUID.randomUUID();
    private Phase phase = Phase.NEW;
    private boolean used;

    private StoreSession() {}

    public UUID epoch() {
      return epoch;
    }

    /** SQLite-only callback; no store operations, callbacks or producer admission may reenter. */
    public void recoverAtStartup(Runnable recovery) {
      Objects.requireNonNull(recovery);
      synchronized (coordinator) {
        ensureOpen();
        if (phase != Phase.NEW || used)
          throw new IllegalStateException("Content recovery requires the unused startup session");
        phase = Phase.RECOVERING;
        try {
          recovery.run();
          phase = Phase.READY;
        } catch (RuntimeException | Error failure) {
          phase = Phase.FAILED;
          throw failure;
        }
      }
    }

    /** Guarded producers call before any admission transaction or network operation. */
    public void beforeProducerOperation() {
      synchronized (coordinator) {
        ensureOpen();
        if (phase != Phase.READY)
          throw new IllegalStateException("Content producer requires completed startup recovery");
        used = true;
      }
    }

    private void legacyOperation() {
      ensureOpen();
      if (phase == Phase.RECOVERING || phase == Phase.FAILED)
        throw new IllegalStateException("Content store startup recovery is not usable");
      used = true;
    }
  }

  private enum Phase {
    NEW,
    RECOVERING,
    READY,
    FAILED
  }

  @Override
  public void publisherStarted() {
    synchronized (coordinator) {
      contentSession.legacyOperation();
    }
  }

  @Override
  public StoredObject publish(
      InputStream source,
      ExpectedObjectChecksum expected,
      long maximumBytes,
      CancellationToken cancellation) {
    Objects.requireNonNull(expected, "expected");
    return publishInternal(source, expected.length(), maximumBytes, expected, null, cancellation);
  }

  @Override
  public StoredObject publishVerified(
      InputStream encodedSource,
      long expectedEncodedLength,
      long maximumBytes,
      CompletionVerification completion,
      CancellationToken cancellation) {
    Objects.requireNonNull(completion, "completion");
    return publishInternal(
        encodedSource, expectedEncodedLength, maximumBytes, null, completion, cancellation);
  }

  private StoredObject publishInternal(
      InputStream source,
      long expectedLength,
      long maximumBytes,
      ExpectedObjectChecksum expectedChecksum,
      CompletionVerification completion,
      CancellationToken cancellation) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(cancellation, "cancellation");
    if (maximumBytes < 0 || expectedLength < 0)
      throw new IllegalArgumentException("Object byte bounds cannot be negative");
    if (expectedLength > maximumBytes)
      throw failure(LIMIT_EXCEEDED, "Object exceeds the configured byte limit");
    beginOperation();
    Path stage = null;
    try {
      cancellation.throwIfCancellationRequested();
      stage = Files.createTempFile(work, "stage-", ".tmp");
      var sourceHash =
          expectedChecksum == null ? null : hash(expectedChecksum.algorithm().jcaName());
      var physicalHash = hash("SHA-256");
      long total = 0;
      try (var output =
          FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
        var buffer = new byte[BUFFER_BYTES];
        while (true) {
          cancellation.throwIfCancellationRequested();
          int read = source.read(buffer);
          if (read < 0) break;
          if (read == 0) {
            cancellation.throwIfCancellationRequested();
            int single = source.read();
            if (single < 0) break;
            buffer[0] = (byte) single;
            read = 1;
          }
          if (read > maximumBytes - total)
            throw failure(LIMIT_EXCEEDED, "Object exceeds the configured byte limit");
          if (read > expectedLength - total)
            throw failure(INTEGRITY_FAILURE, "Object length differs from its expected length");
          if (sourceHash != null) sourceHash.update(buffer, 0, read);
          physicalHash.update(buffer, 0, read);
          var bytes = ByteBuffer.wrap(buffer, 0, read);
          while (bytes.hasRemaining()) output.write(bytes);
          total += read;
        }
        cancellation.throwIfCancellationRequested();
        if (total != expectedLength)
          throw failure(INTEGRITY_FAILURE, "Object length differs from its expected length");
        if (expectedChecksum != null
            && !MessageDigest.isEqual(
                sourceHash.digest(), HexFormat.of().parseHex(expectedChecksum.hexDigest()))) {
          throw failure(INTEGRITY_FAILURE, "Object checksum differs from its expected identity");
        }
        if (completion != null) completion.verifyComplete(cancellation);
        cancellation.throwIfCancellationRequested();
        output.force(true);
      }
      var object =
          new StoredObject(
              new ObjectDigest(HexFormat.of().formatHex(physicalHash.digest())), total);
      synchronized (coordinator) {
        cancellation.throwIfCancellationRequested();
        var target = objectPath(object.digest());
        ensureDirectory(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          try (var existing = openVerifiedChannel(target, object.digest(), cancellation)) {
            if (existing.size() != total || Files.mismatch(stage, target) != -1) {
              throw failure(
                  INTEGRITY_FAILURE, "An existing physical object conflicts with incoming bytes");
            }
          }
        } else {
          // Exclusive process ownership plus this monitor excludes another publisher.
          // Atomic move is required; unsupported filesystems fail instead of publishing partial
          // bytes.
          Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
          stage = null;
        }
      }
      return object;
    } catch (IOException io) {
      throw failure(IO_FAILURE, "Cannot publish verified object bytes", io);
    } finally {
      if (stage != null) {
        try {
          Files.deleteIfExists(stage);
        } catch (IOException ignored) {
          /* Startup removes abandoned app-owned staging files. */
        }
      }
      endOperation();
    }
  }

  @Override
  public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken cancellation) {
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(cancellation, "cancellation");
    beginOperation();
    try {
      cancellation.throwIfCancellationRequested();
      synchronized (coordinator) {
        var path = objectPath(digest);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var channel = openVerifiedChannel(path, digest, cancellation)) {
          return Optional.of(new StoredObject(digest, channel.size()));
        }
      }
    } catch (IOException io) {
      throw failure(IO_FAILURE, "Cannot verify stored object bytes", io);
    } finally {
      endOperation();
    }
  }

  @Override
  public ObjectReadLease openRead(ObjectDigest digest, CancellationToken cancellation) {
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(cancellation, "cancellation");
    synchronized (coordinator) {
      contentSession.legacyOperation();
      cancellation.throwIfCancellationRequested();
      var path = objectPath(digest);
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        throw failure(MISSING_OBJECT, "Object is not stored locally");
      try {
        var channel = openVerifiedChannel(path, digest, cancellation);
        var object = new StoredObject(digest, channel.size());
        readers++;
        return new ReadLease(object, channel);
      } catch (IOException io) {
        throw failure(IO_FAILURE, "Cannot open stored object bytes", io);
      }
    }
  }

  @Override
  public void close() {
    synchronized (coordinator) {
      if (closed) return;
      if (contentSession.phase == Phase.RECOVERING)
        throw new IllegalStateException("Cannot close during startup recovery");
      if (operations != 0 || readers != 0)
        throw failure(BUSY, "Active object operations or readers must close first");
      try {
        processLock.release();
        lockChannel.close();
        closed = true;
      } catch (IOException io) {
        throw failure(IO_FAILURE, "Cannot release object store ownership", io);
      }
    }
  }

  private FileChannel openVerifiedChannel(
      Path path, ObjectDigest expected, CancellationToken cancellation) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw failure(INTEGRITY_FAILURE, "Stored object is not a regular byte file");
    var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try {
      var hash = hash("SHA-256");
      var buffer = ByteBuffer.allocate(BUFFER_BYTES);
      while (true) {
        cancellation.throwIfCancellationRequested();
        int read = channel.read(buffer);
        if (read < 0) break;
        hash.update(buffer.array(), 0, read);
        buffer.clear();
      }
      if (!MessageDigest.isEqual(hash.digest(), HexFormat.of().parseHex(expected.value()))) {
        throw failure(
            INTEGRITY_FAILURE,
            "Stored object failed SHA-256 verification; bytes were preserved for repair");
      }
      channel.position(0);
      return channel;
    } catch (IOException | RuntimeException failure) {
      try {
        channel.close();
      } catch (IOException suppressed) {
        failure.addSuppressed(suppressed);
      }
      throw failure;
    }
  }

  private Path objectPath(ObjectDigest digest) {
    if (!Files.isDirectory(objects, LinkOption.NOFOLLOW_LINKS)) {
      throw failure(INTEGRITY_FAILURE, "Object storage directory is no longer an owned directory");
    }
    var directory = objects.resolve(digest.value().substring(0, 2));
    if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
        && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw failure(INTEGRITY_FAILURE, "Object prefix directory is not an owned directory");
    }
    return directory.resolve(digest.value().substring(2));
  }

  private void beginOperation() {
    synchronized (coordinator) {
      contentSession.legacyOperation();
      operations++;
    }
  }

  private void endOperation() {
    synchronized (coordinator) {
      operations--;
    }
  }

  private void ensureOpen() {
    if (closed) throw failure(CLOSED, "Object store is closed");
  }

  private static void ensureDirectory(Path directory) throws IOException {
    Files.createDirectories(directory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
      throw failure(INTEGRITY_FAILURE, "Object store directory is not an owned directory");
  }

  private static MessageDigest hash(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JDK lacks required checksum algorithm", impossible);
    }
  }

  private static ObjectStoreException failure(ObjectStoreException.Code code, String message) {
    return new ObjectStoreException(code, message);
  }

  private static ObjectStoreException failure(
      ObjectStoreException.Code code, String message, Throwable cause) {
    return new ObjectStoreException(code, message, cause);
  }

  private final class ReadLease implements ObjectReadLease {
    private final StoredObject object;
    private final InputStream stream;
    private final AtomicBoolean released = new AtomicBoolean();

    private ReadLease(StoredObject object, FileChannel channel) {
      this.object = object;
      stream = Channels.newInputStream(channel);
    }

    @Override
    public StoredObject object() {
      return object;
    }

    @Override
    public InputStream stream() {
      return stream;
    }

    @Override
    public void close() {
      if (!released.compareAndSet(false, true)) return;
      try {
        stream.close();
      } catch (IOException io) {
        throw failure(IO_FAILURE, "Cannot close stored object reader", io);
      } finally {
        synchronized (coordinator) {
          readers--;
        }
      }
    }
  }
}
