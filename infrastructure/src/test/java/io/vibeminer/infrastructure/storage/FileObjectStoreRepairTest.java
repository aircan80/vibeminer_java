package io.vibeminer.infrastructure.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.domain.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileObjectStoreRepairTest {
  @TempDir Path root;
  final byte[] bytes =
      "synthetic original repair".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  StoredObject original() throws Exception {
    return new StoredObject(
        new ObjectDigest(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))),
        bytes.length);
  }

  Path target(StoredObject o) {
    return root.resolve("objects")
        .resolve(o.digest().value().substring(0, 2))
        .resolve(o.digest().value().substring(2));
  }

  void cleanStages() throws Exception {
    try (var s = Files.list(root.resolve("work"))) {
      assertEquals(0, s.count());
    }
  }

  StoredObject repair(FileObjectStore store, StoredObject o) {
    return store.repairVerified(
        new ByteArrayInputStream(bytes), o, bytes.length, t -> {}, CancellationToken.none());
  }

  @Test
  void missingAndHealthyTargetsReturnExactOriginalWithoutDuplicateObjects() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      var eof = new AtomicBoolean();
      var input =
          new ByteArrayInputStream(bytes) {
            public synchronized int read(byte[] b, int off, int n) {
              int x = super.read(b, off, n);
              if (x < 0) eof.set(true);
              return x;
            }
          };
      assertEquals(
          o,
          store.repairVerified(
              input,
              o,
              bytes.length,
              t -> {
                assertTrue(eof.get());
                assertFalse(Files.exists(target(o)));
              },
              CancellationToken.none()));
      assertEquals(o, repair(store, o));
      assertArrayEquals(bytes, Files.readAllBytes(target(o)));
      cleanStages();
      try (var s = Files.walk(root.resolve("objects"))) {
        assertEquals(1, s.filter(Files::isRegularFile).count());
      }
    }
  }

  @Test
  void corruptRegularTargetReplacedAtomicallyButOrdinaryPublishStillRejectsIt() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      repair(store, o);
      Files.writeString(target(o), "corrupt");
      assertThrows(
          ObjectStoreException.class,
          () ->
              store.publishVerified(
                  new ByteArrayInputStream(bytes),
                  bytes.length,
                  bytes.length,
                  t -> {},
                  CancellationToken.none()));
      assertEquals("corrupt", Files.readString(target(o)));
      assertEquals(o, repair(store, o));
      assertEquals(Optional.of(o), store.verify(o.digest(), CancellationToken.none()));
      cleanStages();
    }
  }

  @Test
  void badInputOrGateOrCancellationPreservesCorruptTargetAndRemovesStages() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      repair(store, o);
      Files.writeString(target(o), "corrupt");
      for (byte[] bad :
          List.of(
              Arrays.copyOf(bytes, bytes.length - 1),
              Arrays.copyOf(bytes, bytes.length + 1),
              new byte[bytes.length])) {
        assertThrows(
            ObjectStoreException.class,
            () ->
                store.repairVerified(
                    new ByteArrayInputStream(bad),
                    o,
                    bytes.length + 1,
                    t -> fail("Invalid bytes cannot reach gate"),
                    CancellationToken.none()));
        assertEquals("corrupt", Files.readString(target(o)));
        cleanStages();
      }
      assertThrows(
          IllegalStateException.class,
          () ->
              store.repairVerified(
                  new ByteArrayInputStream(bytes),
                  o,
                  bytes.length,
                  t -> {
                    throw new IllegalStateException("gate");
                  },
                  CancellationToken.none()));
      var cancel = new AtomicBoolean();
      assertThrows(
          CancellationException.class,
          () ->
              store.repairVerified(
                  new ByteArrayInputStream(bytes),
                  o,
                  bytes.length,
                  t -> cancel.set(true),
                  cancel::get));
      assertEquals("corrupt", Files.readString(target(o)));
      cleanStages();
    }
  }

  @Test
  void anyActiveReadLeaseBlocksRepairAndLeaseRetirementAllowsIt() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      repair(store, o);
      try (var lease = store.openRead(o.digest(), CancellationToken.none())) {
        assertEquals(
            ObjectStoreException.Code.BUSY,
            assertThrows(ObjectStoreException.class, () -> repair(store, o)).code());
        assertArrayEquals(bytes, lease.stream().readAllBytes());
        cleanStages();
      }
      assertEquals(o, repair(store, o));
    }
  }

  @Test
  void symlinkAndDirectoryTargetsAreNeverReplaced() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      Files.createDirectories(target(o).getParent());
      Files.createDirectory(target(o));
      assertThrows(ObjectStoreException.class, () -> repair(store, o));
      assertTrue(Files.isDirectory(target(o)));
      Files.delete(target(o));
      var external = root.resolve("external");
      Files.writeString(external, "untouched");
      try {
        Files.createSymbolicLink(target(o), external);
      } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
        return;
      }
      assertThrows(ObjectStoreException.class, () -> repair(store, o));
      assertTrue(Files.isSymbolicLink(target(o)));
      assertEquals("untouched", Files.readString(external));
      cleanStages();
    }
  }

  @Test
  void unsupportedAtomicReplacementPreservesOldTargetWithoutFallback() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      repair(store, o);
      Files.writeString(target(o), "corrupt");
    }
    try (var store =
        new FileObjectStore(
            root,
            (source, target, replace) -> {
              assertTrue(replace);
              throw new AtomicMoveNotSupportedException(
                  source.toString(), target.toString(), "synthetic unsupported atomic replacement");
            })) {
      assertEquals(
          ObjectStoreException.Code.IO_FAILURE,
          assertThrows(ObjectStoreException.class, () -> repair(store, o)).code());
      assertEquals("corrupt", Files.readString(target(o)));
      cleanStages();
    }
  }

  @Test
  void boundsAndFailedStartupGateRejectRepairBeforeVerifier() throws Exception {
    var o = original();
    try (var store = new FileObjectStore(root)) {
      assertEquals(
          ObjectStoreException.Code.LIMIT_EXCEEDED,
          assertThrows(
                  ObjectStoreException.class,
                  () ->
                      store.repairVerified(
                          new ByteArrayInputStream(bytes),
                          o,
                          bytes.length - 1,
                          t -> fail("gate"),
                          CancellationToken.none()))
              .code());
      assertThrows(
          IllegalStateException.class,
          () ->
              store
                  .contentSession()
                  .recoverAtStartup(
                      () -> {
                        throw new IllegalStateException("failed startup");
                      }));
      assertThrows(IllegalStateException.class, () -> repair(store, o));
      cleanStages();
    }
  }
}
