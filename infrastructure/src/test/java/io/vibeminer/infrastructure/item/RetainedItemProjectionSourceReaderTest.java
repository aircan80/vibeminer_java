package io.vibeminer.infrastructure.item;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class RetainedItemProjectionSourceReaderTest {
  SelectedFileAssociation association =
      ItemSourceFixture.association("12.1.5.70448", Instant.EPOCH);
  final ItemProjectionRequest request =
      new ItemProjectionRequest(
          association.descriptor(),
          "enUS",
          new ItemProjectionLimits(10000, 4096, 100, 100, 4096, 4096));
  byte[] bytes = ItemSourceFixture.encoded();
  int leases, inspections;
  boolean missing;
  Clock clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC);

  @SuppressWarnings("unchecked")
  static <T> T port(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  RetainedItemProjectionSourceReader reader() {
    var catalog =
        port(
            SelectedFileCatalog.class,
            (p, m, a) -> {
              if (!m.getName().equals("findOriginalAssociation"))
                throw new AssertionError("Unexpected catalog operation " + m);
              return missing ? Optional.empty() : Optional.of(association);
            });
    var gateway =
        port(
            SelectedFileGateway.class,
            (p, m, a) -> {
              if (!m.getName().equals("inspectRetained"))
                throw new AssertionError("Network capable operation forbidden " + m);
              inspections++;
              return association.representation();
            });
    var store =
        port(
            ObjectStore.class,
            (p, m, a) -> {
              if (!m.getName().equals("openRead"))
                throw new AssertionError("Unexpected mutation " + m);
              leases++;
              return new ObjectReadLease() {
                boolean closed;

                public io.vibeminer.domain.StoredObject object() {
                  return association.representation().encoded().object();
                }

                public InputStream stream() {
                  return new ByteArrayInputStream(bytes);
                }

                public void close() {
                  if (!closed) {
                    closed = true;
                    leases--;
                  }
                }
              };
            });
    return new RetainedItemProjectionSourceReader(store, catalog, gateway, clock);
  }

  @Test
  void completeSyntheticPipelineHoldsLeaseThroughRowsAndPublicationBoundary() {
    try (var opened = reader().open(request, CancellationToken.none())) {
      assertEquals(1, leases);
      assertEquals(1, inspections);
      assertTrue(opened.evidence().fullDecoded().isEmpty());
      assertEquals(
          List.of(new ItemProjectionSourceReader.CopyDeclaration(303, 101)), opened.copies());
      var rows = new ArrayList<ItemPhysicalRow>();
      var coverage = opened.project(rows::add, CancellationToken.none());
      assertEquals(List.of(101L, 202L), rows.stream().map(ItemPhysicalRow::recordId).toList());
      assertEquals(3, rows.getFirst().values().getFirst().candidateValue());
      assertEquals(2, coverage.physicalRows());
      assertEquals(1, coverage.unindexedCopyDeclarations());
      var handle = new PreparedItemProjection(opened.definition(), opened.evidence(), coverage);
      clock = Clock.fixed(Instant.ofEpochSecond(200), ZoneOffset.UTC);
      try (var verified = reader().verify(handle, CancellationToken.none())) {
        assertEquals(2, leases);
        assertEquals(
            Instant.ofEpochSecond(100), handle.evidence().localVerification().verifiedAt());
      }
      assertEquals(1, leases);
    }
    assertEquals(0, leases);
  }

  void replaceEncoded(byte[] encoded) {
    bytes = encoded;
    association =
        ItemSourceFixture.association(
            "12.1.5.70448", Instant.EPOCH, encoded, ItemSourceFixture.decoded().length);
  }

  ItemProjectionRequest currentRequest() {
    return new ItemProjectionRequest(association.descriptor(), "enUS", request.limits());
  }

  @Test
  void authenticatedUnsupportedChunkAndZeroHeaderRemainOpaque() {
    var opaque = ItemSourceFixture.encoded();
    opaque[36] = 'E';
    System.arraycopy(
        ItemSourceFixture.digest("MD5", Arrays.copyOfRange(opaque, 36, opaque.length)),
        0,
        opaque,
        20,
        16);
    replaceEncoded(opaque);
    assertEquals(
        ItemProjectionException.Code.UNSUPPORTED,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().open(currentRequest(), CancellationToken.none()))
            .code());
    assertEquals(0, leases);
    var zero = new byte[9 + ItemSourceFixture.decoded().length];
    System.arraycopy(new byte[] {'B', 'L', 'T', 'E'}, 0, zero, 0, 4);
    zero[8] = 'N';
    System.arraycopy(ItemSourceFixture.decoded(), 0, zero, 9, ItemSourceFixture.decoded().length);
    replaceEncoded(zero);
    // The original receipt's scope is separately authenticated; select its matching zero-header
    // scope.
    var a = association;
    var rep = a.representation();
    association =
        new SelectedFileAssociation(
            a.descriptor(),
            a.configuration(),
            a.root(),
            a.proof(),
            new ContentRepresentation(
                rep.slot(),
                rep.encoded(),
                EncodedScope.ZERO_HEADER_WHOLE_ENCODED,
                rep.sourceReceipt()));
    assertEquals(
        ItemProjectionException.Code.UNSUPPORTED,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().open(currentRequest(), CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }

  @Test
  void lateDuplicatePhysicalIdNeverReturnsCompletion() {
    var decoded = ItemSourceFixture.decoded();
    java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(690, 101);
    replaceEncoded(ItemSourceFixture.encode(decoded));
    try (var opened = reader().open(currentRequest(), CancellationToken.none())) {
      var rows = new ArrayList<ItemPhysicalRow>();
      assertEquals(
          ItemProjectionException.Code.CORRUPT,
          assertThrows(
                  ItemProjectionException.class,
                  () -> opened.project(rows::add, CancellationToken.none()))
              .code());
      assertTrue(rows.isEmpty());
    }
    assertEquals(0, leases);
  }

  @Test
  void explicitLocalBoundsRejectBeforeUnboundedMaterialization() {
    var limited =
        new ItemProjectionRequest(
            association.descriptor(),
            "enUS",
            new ItemProjectionLimits(1, 4096, 100, 100, 4096, 4096));
    assertEquals(
        ItemProjectionException.Code.LIMIT_EXCEEDED,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().open(limited, CancellationToken.none()))
            .code());
    assertEquals(0, leases);
    assertEquals(0, inspections);
  }

  @Test
  void lateCandidateOverflowLeavesOnlyProvisionalRows() {
    var decoded = ItemSourceFixture.decoded();
    var b = java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.putShort(330, (short) 16);
    b.putInt(344, 16);
    decoded[676] = 1;
    replaceEncoded(ItemSourceFixture.encode(decoded));
    try (var opened = reader().open(currentRequest(), CancellationToken.none())) {
      var provisional = new ArrayList<ItemPhysicalRow>();
      assertEquals(
          ItemProjectionException.Code.INCOMPATIBLE,
          assertThrows(
                  ItemProjectionException.class,
                  () -> opened.project(provisional::add, CancellationToken.none()))
              .code());
      assertEquals(1, provisional.size());
      assertEquals(101, provisional.getFirst().recordId());
    }
    assertEquals(0, leases);
  }

  @Test
  void queryVerificationRejectsAlteredInterpretationAndConsumedProof() {
    PreparedItemProjection handle;
    try (var opened = reader().open(request, CancellationToken.none())) {
      var coverage = opened.project(row -> {}, CancellationToken.none());
      var e = opened.evidence();
      var altered =
          new ItemProjectionEvidence(
              e.association(),
              e.localVerification(),
              e.chunks(),
              List.of(e.windows().getLast()),
              Optional.empty());
      handle = new PreparedItemProjection(opened.definition(), altered, coverage);
    }
    assertEquals(
        ItemProjectionException.Code.CORRUPT,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().verify(handle, CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }

  @Test
  void queryVerificationRejectsInventedCoverageCounts() {
    PreparedItemProjection handle;
    try (var opened = reader().open(request, CancellationToken.none())) {
      opened.project(row -> {}, CancellationToken.none());
      handle =
          new PreparedItemProjection(
              opened.definition(),
              opened.evidence(),
              new PreparedItemProjection.Coverage(0, 3, 1, 0));
    }
    assertEquals(
        ItemProjectionException.Code.CORRUPT,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().verify(handle, CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }

  @Test
  void corruptionIsRejectedBeforeAnyRowAndReleasesLease() {
    bytes[bytes.length - 1] ^= 1;
    assertEquals(
        ItemProjectionException.Code.CORRUPT,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().open(request, CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }

  @Test
  void missingOrCancelledNeverAcquiresBytes() {
    missing = true;
    assertEquals(
        ItemProjectionException.Code.MISSING_LOCAL,
        assertThrows(
                ItemProjectionException.class,
                () -> reader().open(request, CancellationToken.none()))
            .code());
    assertThrows(CancellationException.class, () -> reader().open(request, () -> true));
    assertEquals(0, leases);
    assertEquals(0, inspections);
  }

  @Test
  void sinkFailureAndLateCancellationExposeNoCompletionAndKeepOwnedLease() {
    try (var opened = reader().open(request, CancellationToken.none())) {
      assertThrows(
          ItemProjectionException.class,
          () ->
              opened.project(
                  row -> {
                    throw new IllegalStateException("staging failed");
                  },
                  CancellationToken.none()));
      var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
      var rows = new ArrayList<ItemPhysicalRow>();
      assertThrows(
          CancellationException.class,
          () ->
              opened.project(
                  row -> {
                    rows.add(row);
                    cancelled.set(true);
                  },
                  cancelled::get));
      assertEquals(1, rows.size());
      assertEquals(1, leases);
    }
    assertEquals(0, leases);
  }
}
