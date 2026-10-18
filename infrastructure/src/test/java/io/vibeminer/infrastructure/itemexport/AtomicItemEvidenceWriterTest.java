package io.vibeminer.infrastructure.itemexport;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemexport.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicItemEvidenceWriterTest {
  @TempDir Path directory;
  final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

  Path target() {
    return directory.resolve("finding.json");
  }

  long files() throws IOException {
    try (var files = Files.list(directory)) {
      return files.count();
    }
  }

  @Test
  void explicitlyAuthorizedAtomicReplacementReturnsActualReceipt() throws Exception {
    var destination = target();
    Files.writeString(destination, "previous");
    var receipt =
        new AtomicItemEvidenceWriter(clock)
            .publish(
                ItemExportFixture.snapshot(), destination.toString(), CancellationToken.none());
    byte[] bytes = Files.readAllBytes(destination);
    assertEquals(bytes.length, receipt.encodedUtf8Bytes());
    assertEquals(
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
        receipt.sha256());
    assertEquals(Instant.EPOCH, receipt.committedAt());
    assertEquals(1, files());
  }

  @Test
  void absentPreviewDoesNotPreventAuthorizedReplacementOfTargetCreatedAtCommit() throws Exception {
    assertFalse(Files.exists(target()));
    var writer =
        new AtomicItemEvidenceWriter(
            clock,
            (sibling, destination) -> {
              Files.writeString(destination, "created by another actor after preview");
              Files.move(sibling, destination, StandardCopyOption.ATOMIC_MOVE);
            });
    writer.publish(ItemExportFixture.snapshot(), target().toString(), CancellationToken.none());
    assertTrue(Files.readString(target()).startsWith("{\"format\":"));
    assertEquals(1, files());
  }

  @Test
  void confirmedRefusalAndUnsupportedAtomicMovePreserveTargetAndCleanOwnedSibling()
      throws Exception {
    for (boolean unsupported : new boolean[] {false, true}) {
      Files.writeString(target(), "original");
      var writer =
          new AtomicItemEvidenceWriter(
              clock,
              (sibling, destination) -> {
                assertNotEquals(sibling, destination);
                assertTrue(Files.exists(sibling));
                if (unsupported)
                  throw new AtomicMoveNotSupportedException("fixture", "fixture", "unsupported");
                throw new FileAlreadyExistsException("fixture");
              });
      var failure =
          assertThrows(
              ItemEvidenceException.class,
              () ->
                  writer.publish(
                      ItemExportFixture.snapshot(), target().toString(), CancellationToken.none()));
      assertEquals(
          unsupported
              ? ItemEvidenceException.Code.ATOMIC_PUBLICATION_UNSUPPORTED
              : ItemEvidenceException.Code.DESTINATION_REFUSED,
          failure.code());
      assertEquals("original", Files.readString(target()));
      assertEquals(1, files());
    }
  }

  @Test
  void unknownProviderOutcomeNeverRollsBackOrInventsSuccessfulReceipt() throws Exception {
    for (boolean committed : new boolean[] {false, true}) {
      Files.writeString(target(), "original");
      var writer =
          new AtomicItemEvidenceWriter(
              clock,
              (sibling, destination) -> {
                if (committed) Files.move(sibling, destination, StandardCopyOption.ATOMIC_MOVE);
                throw new IOException("provider cannot determine outcome");
              });
      assertEquals(
          ItemEvidenceException.Code.COMMIT_UNCERTAIN,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      writer.publish(
                          ItemExportFixture.snapshot(),
                          target().toString(),
                          CancellationToken.none()))
              .code());
      if (committed) assertTrue(Files.readString(target()).startsWith("{"));
      else assertEquals("original", Files.readString(target()));
      // The uncertain staging entry is deliberately preserved for inspection, never rolled back.
      try (var entries = Files.list(directory)) {
        for (var entry : entries.toList()) if (!entry.equals(target())) Files.delete(entry);
      }
    }
  }

  @Test
  void cancellationBeforeCommitPreservesTargetAndCleansOwnedSibling() throws Exception {
    Files.writeString(target(), "original");
    var count = new AtomicInteger();
    new ItemEvidenceJsonEncoder()
        .encode(
            ItemExportFixture.snapshot(),
            Instant.EPOCH,
            () -> {
              count.incrementAndGet();
              return false;
            });
    int cancellationCheck = count.get() + 4;
    count.set(0);
    var writer =
        new AtomicItemEvidenceWriter(
            clock,
            (sibling, destination) -> {
              throw new AssertionError("Canceled publication cannot enter atomic move");
            });
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            writer.publish(
                ItemExportFixture.snapshot(),
                target().toString(),
                () -> count.incrementAndGet() >= cancellationCheck));
    assertEquals("original", Files.readString(target()));
    assertEquals(1, files());
  }

  @Test
  void confirmedCommitWinsCancellationAndBackwardWallClock() throws Exception {
    var cancelled = new AtomicBoolean();
    var calls = new AtomicInteger();
    Clock backwards =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId z) {
            return this;
          }

          public Instant instant() {
            return calls.getAndIncrement() == 0 ? Instant.EPOCH.plusSeconds(20) : Instant.EPOCH;
          }
        };
    var writer =
        new AtomicItemEvidenceWriter(
            backwards,
            (sibling, destination) -> {
              Files.move(sibling, destination, StandardCopyOption.ATOMIC_MOVE);
              cancelled.set(true);
            });
    var receipt = writer.publish(ItemExportFixture.snapshot(), target().toString(), cancelled::get);
    assertEquals(Instant.EPOCH, receipt.committedAt());
    assertTrue(Files.readString(target()).contains("1970-01-01T00:00:20Z"));
    assertEquals(1, files());
  }

  @Test
  void postCommitClockFaultUsesActualFallbackObservationWhilePreCommitFaultWritesNothing()
      throws Exception {
    var calls = new AtomicInteger();
    Clock failsAfterCreation =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId z) {
            return this;
          }

          public Instant instant() {
            if (calls.getAndIncrement() == 0) return Instant.EPOCH;
            throw new IllegalStateException("clock unavailable");
          }
        };
    Instant before = Instant.now();
    var receipt =
        new AtomicItemEvidenceWriter(failsAfterCreation)
            .publish(ItemExportFixture.snapshot(), target().toString(), CancellationToken.none());
    assertFalse(receipt.committedAt().isBefore(before));
    assertFalse(receipt.committedAt().isAfter(Instant.now()));
    Files.delete(target());
    assertEquals(
        ItemEvidenceException.Code.STORAGE_UNAVAILABLE,
        assertThrows(
                ItemEvidenceException.class,
                () ->
                    new AtomicItemEvidenceWriter(failsAfterCreation)
                        .publish(
                            ItemExportFixture.snapshot(),
                            target().toString(),
                            CancellationToken.none()))
            .code());
    assertEquals(0, files());
  }

  @Test
  void destinationValidationIsPureBoundedAndRequiresNoExistingParent() throws Exception {
    var writer = new AtomicItemEvidenceWriter(clock);
    assertDoesNotThrow(
        () ->
            writer.validateDestination(
                directory.resolve("does-not-exist/finding.json").toString()));
    for (String invalid :
        new String[] {
          "relative.json",
          directory.resolve("child/../finding.json").toString(),
          "x".repeat(32769),
          directory.toString() + "/bad\ud800.json"
        }) {
      assertEquals(
          ItemEvidenceException.Code.INVALID_REQUEST,
          assertThrows(ItemEvidenceException.class, () -> writer.validateDestination(invalid))
              .code());
    }
    assertEquals(0, files());
  }

  @Test
  void invalidPathOversizeUnicodeAndStagingFailureNeverTouchChosenTarget() throws Exception {
    Files.writeString(target(), "original");
    var writer = new AtomicItemEvidenceWriter(clock);
    for (String invalid :
        new String[] {"relative.json", directory.resolve("child/../finding.json").toString()})
      assertEquals(
          ItemEvidenceException.Code.INVALID_REQUEST,
          assertThrows(
                  ItemEvidenceException.class,
                  () ->
                      writer.publish(
                          ItemExportFixture.snapshot(), invalid, CancellationToken.none()))
              .code());
    assertEquals(
        ItemEvidenceException.Code.INVALID_REQUEST,
        assertThrows(
                ItemEvidenceException.class,
                () ->
                    writer.publish(
                        ItemExportFixture.edition("bad\ud800", java.util.List.of()),
                        target().toString(),
                        CancellationToken.none()))
            .code());
    assertEquals(
        ItemEvidenceException.Code.LIMIT_EXCEEDED,
        assertThrows(
                ItemEvidenceException.class,
                () ->
                    writer.publish(
                        ItemExportFixture.edition("x".repeat(2097152), java.util.List.of()),
                        target().toString(),
                        CancellationToken.none()))
            .code());
    assertEquals(
        ItemEvidenceException.Code.STORAGE_UNAVAILABLE,
        assertThrows(
                ItemEvidenceException.class,
                () ->
                    writer.publish(
                        ItemExportFixture.snapshot(),
                        directory.resolve("missing/output.json").toString(),
                        CancellationToken.none()))
            .code());
    assertEquals("original", Files.readString(target()));
    assertEquals(1, files());
  }
}
