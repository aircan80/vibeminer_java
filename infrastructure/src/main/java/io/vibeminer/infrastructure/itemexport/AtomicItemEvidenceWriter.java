package io.vibeminer.infrastructure.itemexport;

import static io.vibeminer.application.itemexport.ItemEvidenceException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemexport.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** One explicitly authorized pathname, bounded sibling staging and atomic publication only. */
public final class AtomicItemEvidenceWriter implements ItemEvidenceWriter {
  @FunctionalInterface
  interface AtomicMove {
    void publish(Path sibling, Path authorizedDestination) throws IOException;
  }

  private final Clock clock;
  private final AtomicMove move;
  private final ItemEvidenceJsonEncoder encoder = new ItemEvidenceJsonEncoder();

  public AtomicItemEvidenceWriter(Clock clock) {
    this(
        clock,
        (sibling, destination) -> Files.move(sibling, destination, StandardCopyOption.ATOMIC_MOVE));
  }

  AtomicItemEvidenceWriter(Clock clock, AtomicMove move) {
    this.clock = Objects.requireNonNull(clock);
    this.move = Objects.requireNonNull(move);
  }

  /** Pure bounded platform grammar check; no filesystem observation or normalization. */
  @Override
  public void validateDestination(String authorizedReplacementPath) {
    destination(authorizedReplacementPath);
  }

  @Override
  public ItemEvidenceReceipt publish(
      ItemEvidenceSnapshot snapshot,
      String authorizedReplacementPath,
      CancellationToken cancellation) {
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    validateDestination(authorizedReplacementPath);
    Path destination = Path.of(authorizedReplacementPath);
    Instant created;
    try {
      created = clock.instant();
    } catch (RuntimeException failed) {
      throw new ItemEvidenceException(
          STORAGE_UNAVAILABLE, "Cannot observe export creation time", failed);
    }
    byte[] encoded = encoder.encode(snapshot, created, cancellation);
    String sha256 = digest(encoded);
    cancellation.throwIfCancellationRequested();
    Path sibling = destination.resolveSibling(".vibeminer-export-" + UUID.randomUUID() + ".tmp");
    boolean owned = false;
    boolean safeCleanup = true;
    Throwable primary = null;
    try {
      // CREATE_NEW owns only this random sibling. The chosen target is never opened or truncated.
      try (var channel =
          FileChannel.open(sibling, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        owned = true;
        var buffer = ByteBuffer.wrap(encoded);
        while (buffer.hasRemaining()) {
          cancellation.throwIfCancellationRequested();
          channel.write(buffer);
        }
      }
      cancellation.throwIfCancellationRequested();
      try {
        safeCleanup = false;
        move.publish(sibling, destination);
      } catch (AtomicMoveNotSupportedException unsupported) {
        safeCleanup = true;
        throw new ItemEvidenceException(
            ATOMIC_PUBLICATION_UNSUPPORTED,
            "This destination does not support the required atomic publication",
            unsupported);
      } catch (FileAlreadyExistsException refused) {
        safeCleanup = true;
        throw new ItemEvidenceException(
            DESTINATION_REFUSED, "The destination provider refused atomic replacement", refused);
      } catch (IOException | RuntimeException uncertain) {
        throw new ItemEvidenceException(
            COMMIT_UNCERTAIN,
            "Atomic publication outcome is unknown; inspect the authorized destination",
            uncertain);
      }
      // The successful move is the commit point. Cancellation and clock faults cannot undo it.
      owned = false;
      Instant committed;
      try {
        committed = clock.instant();
      } catch (RuntimeException clockFailure) {
        committed = Clock.systemUTC().instant();
      }
      return new ItemEvidenceReceipt(encoded.length, sha256, committed);
    } catch (IOException failed) {
      var typed =
          new ItemEvidenceException(STORAGE_UNAVAILABLE, "Cannot stage export output", failed);
      primary = typed;
      throw typed;
    } catch (RuntimeException | Error failed) {
      primary = failed;
      throw failed;
    } finally {
      if (owned && safeCleanup) {
        try {
          Files.deleteIfExists(sibling);
        } catch (IOException | RuntimeException cleanup) {
          if (primary != null) primary.addSuppressed(cleanup);
        }
      }
    }
  }

  private static Path destination(String authorized) {
    try {
      if (authorized == null
          || authorized.isBlank()
          || authorized.length() > 32768
          || authorized.indexOf('\0') >= 0
          || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(authorized))
        throw new IllegalArgumentException("Bounded Unicode pathname required");
      var path = Path.of(authorized);
      if (!path.isAbsolute()
          || !path.equals(path.normalize())
          || !authorized.equals(path.toString())
          || path.getFileName() == null
          || path.getParent() == null)
        throw new IllegalArgumentException("Exact absolute normalized destination required");
      if (!path.getFileSystem().equals(FileSystems.getDefault()))
        throw new IllegalArgumentException("Only the qualified native filesystem is supported");
      return path;
    } catch (RuntimeException invalid) {
      throw new ItemEvidenceException(
          INVALID_REQUEST,
          "Authorize an exact absolute lexically normalized output pathname",
          invalid);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
