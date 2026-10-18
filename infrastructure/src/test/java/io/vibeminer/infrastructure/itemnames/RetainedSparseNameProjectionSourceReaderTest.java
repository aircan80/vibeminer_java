package io.vibeminer.infrastructure.itemnames;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class RetainedSparseNameProjectionSourceReaderTest {
  @Test
  void materializationLimitPrecedesSeparateRootReplayAndCasRead() {
    var l = limits;
    var small =
        new SparseNameLimits(
            1,
            l.maxHeaderBytes(),
            l.maxChunks(),
            l.maxChunkEncodedBytes(),
            l.maxChunkDecodedBytes(),
            l.maxMetadataBytes(),
            l.maxSuffixBytes(),
            l.maxPhysicalRows(),
            l.maxCopyDeclarations(),
            l.maxRecordAreaBytes(),
            l.maxRecordBytes(),
            l.maxStringBytes(),
            l.maxPrefixBytes(),
            l.maxProjectedUtf8Bytes(),
            l.maxDecodedWorkBytes(),
            l.maxStagingRows());
    var request = new ItemNamesRequest(request().item(), association.descriptor(), "enUS", small);
    assertEquals(
        ItemNamesException.Code.LIMIT_EXCEEDED,
        assertThrows(
                ItemNamesException.class, () -> reader().open(request, CancellationToken.none()))
            .code());
    assertEquals(0, inspections);
    assertEquals(0, leases);
  }

  @Test
  void queryReplaysExactSemanticRecipeAndPhysicalCoverageCounts() {
    PreparedSparseNames original;
    try (var opened = reader().open(request(), CancellationToken.none())) {
      original = opened.project(r -> {}, CancellationToken.none());
    }
    var i = original.definition().interpretation();
    var altered =
        new SparseNameArtifactDefinition.Interpretation(
            i.decoderRevision() + "-changed",
            i.indexRevision(),
            i.tableHash(),
            i.layoutHash(),
            i.sectionOrdinal(),
            i.metadataPrefixBytes(),
            i.recordAreaOffset(),
            i.recordAreaBytes(),
            i.suffixOffset(),
            i.suffixBytes(),
            i.schema(),
            i.locale(),
            i.optionsRevision(),
            i.proofSemantics());
    var other =
        new PreparedSparseNames(
            new SparseNameArtifactDefinition(original.definition().sources(), altered),
            original.evidence(),
            original.coverage());
    assertEquals(
        ItemNamesException.Code.CORRUPT,
        assertThrows(
                ItemNamesException.class, () -> reader().verify(other, CancellationToken.none()))
            .code());
    var c = original.coverage();
    var wrongCounts =
        new PreparedSparseNames(
            original.definition(),
            original.evidence(),
            new PreparedSparseNames.Coverage(
                c.sectionOrdinal(),
                3,
                c.unindexedCopyDeclarations(),
                c.excludedSections(),
                c.projectedUtf8Bytes()));
    assertEquals(
        ItemNamesException.Code.CORRUPT,
        assertThrows(
                ItemNamesException.class,
                () -> reader().verify(wrongCounts, CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }

  @Test
  void verificationTimeRecordsPhysicalHashCompletionNotLaterProjection() {
    var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.ofEpochSecond(100));
    clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    try (var opened = reader().open(request(), CancellationToken.none())) {
      now.set(Instant.ofEpochSecond(200));
      var prepared = opened.project(r -> {}, CancellationToken.none());
      assertEquals(
          Instant.ofEpochSecond(100), prepared.evidence().localVerification().verifiedAt());
    }
  }

  SelectedFileAssociation association = SparseNameSourceFixture.association();
  byte[] bytes = SparseNameSourceFixture.encoded();
  int leases, inspections;
  boolean missing;
  Clock clock = Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC);
  final SparseNameLimits limits =
      new SparseNameLimits(
          10000, 4096, 100, 10000, 10000, 4096, 4096, 100, 100, 10000, 1000, 200, 1000, 1000,
          100000, 100);

  ItemNamesRequest request() {
    return new ItemNamesRequest(
        PairedItemFixture.item(Instant.EPOCH), association.descriptor(), "enUS", limits);
  }

  @SuppressWarnings("unchecked")
  static <T> T port(Class<T> t, java.lang.reflect.InvocationHandler h) {
    return (T) Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[] {t}, h);
  }

  RetainedSparseNameProjectionSourceReader reader() {
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
                throw new AssertionError("Transport forbidden " + m);
              inspections++;
              return association.representation();
            });
    var store =
        port(
            ObjectStore.class,
            (p, m, a) -> {
              if (!m.getName().equals("openRead"))
                throw new AssertionError("Unexpected storage operation " + m);
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
    return new RetainedSparseNameProjectionSourceReader(store, catalog, gateway, clock);
  }

  @Test
  void streamsCrossChunkRecordsAndReturnsEvidenceOnlyAfterCompletion() {
    try (var opened = reader().open(request(), CancellationToken.none())) {
      assertEquals(1, leases);
      assertEquals(1, inspections);
      assertEquals(1, opened.copies().size());
      var rows = new ArrayList<DisplayCandidate>();
      var names = opened.project(rows::add, CancellationToken.none());
      assertEquals(List.of("Authored α", ""), rows.stream().map(DisplayCandidate::value).toList());
      assertEquals(2, names.coverage().physicalRows());
      assertEquals(1, names.coverage().unindexedCopyDeclarations());
      assertEquals(4, names.evidence().chunks().size());
      assertTrue(names.evidence().fullDecoded().isEmpty());
      assertEquals(opened.definition(), names.definition());
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          assertThrows(
                  ItemNamesException.class, () -> opened.project(r -> {}, CancellationToken.none()))
              .code());
      clock = Clock.fixed(Instant.ofEpochSecond(200), ZoneOffset.UTC);
      try (var verified = reader().verify(names, CancellationToken.none())) {
        assertEquals(2, leases);
        assertEquals(Instant.ofEpochSecond(100), names.evidence().localVerification().verifiedAt());
      }
    }
    assertEquals(0, leases);
  }

  @Test
  void corruptPhysicalSourceFailsBeforeNamesAndReleasesLease() {
    bytes[bytes.length - 1] ^= 1;
    assertEquals(
        ItemNamesException.Code.CORRUPT,
        assertThrows(
                ItemNamesException.class, () -> reader().open(request(), CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }

  @Test
  void missingAndPrecancellationAvoidSourceWork() {
    missing = true;
    assertEquals(
        ItemNamesException.Code.MISSING_LOCAL,
        assertThrows(
                ItemNamesException.class, () -> reader().open(request(), CancellationToken.none()))
            .code());
    assertThrows(CancellationException.class, () -> reader().open(request(), () -> true));
    assertEquals(0, leases);
    assertEquals(0, inspections);
  }

  @Test
  void lateCallbackFailureOrCancellationNeverReturnsPreparedNames() {
    try (var opened = reader().open(request(), CancellationToken.none())) {
      assertThrows(
          ItemNamesException.class,
          () ->
              opened.project(
                  r -> {
                    throw new IllegalStateException("staging failed");
                  },
                  CancellationToken.none()));
      assertEquals(1, leases);
      assertEquals(
          ItemNamesException.Code.INVALID_REQUEST,
          assertThrows(
                  ItemNamesException.class, () -> opened.project(r -> {}, CancellationToken.none()))
              .code());
    }
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var rows = new ArrayList<DisplayCandidate>();
    try (var opened = reader().open(request(), CancellationToken.none())) {
      assertThrows(
          CancellationException.class,
          () ->
              opened.project(
                  r -> {
                    rows.add(r);
                    cancelled.set(true);
                  },
                  cancelled::get));
      assertEquals(1, rows.size());
      assertEquals(1, leases);
    }
    assertEquals(0, leases);
  }

  ItemNamesRequest workBound(long maxWork) {
    return new ItemNamesRequest(
        request().item(),
        association.descriptor(),
        "enUS",
        new SparseNameLimits(
            10000, 4096, 100, 10000, 10000, 4096, 4096, 100, 100, 10000, 1000, 200, 1000, 1000,
            maxWork, 100));
  }

  void replace(byte[] encoded) {
    bytes = encoded;
    association =
        SparseNameSourceFixture.association(
            "12.1.5.70448", Instant.EPOCH, encoded, SparseNameSourceFixture.decoded().length);
  }

  @Test
  void onePassChunkWorkBoundCoversSharedRecordChunksWithoutPerRowInflation() {
    long exact = SparseNameSourceFixture.decoded().length;
    try (var opened = reader().open(workBound(exact), CancellationToken.none())) {
      assertEquals(2, opened.project(r -> {}, CancellationToken.none()).coverage().physicalRows());
    }
    try (var opened = reader().open(workBound(exact - 1), CancellationToken.none())) {
      assertEquals(
          ItemNamesException.Code.LIMIT_EXCEEDED,
          assertThrows(
                  ItemNamesException.class, () -> opened.project(r -> {}, CancellationToken.none()))
              .code());
    }
    assertEquals(0, leases);
  }

  @Test
  void authenticatedOpaqueRecordChunkCannotYieldReadableNames() {
    var encoded = SparseNameSourceFixture.encoded();
    var b = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.BIG_ENDIAN);
    int at = b.getInt(4) + b.getInt(12), length = b.getInt(36);
    encoded[at] = 'E';
    System.arraycopy(
        SparseNameSourceFixture.digest("MD5", Arrays.copyOfRange(encoded, at, at + length)),
        0,
        encoded,
        44,
        16);
    replace(encoded);
    try (var opened = reader().open(request(), CancellationToken.none())) {
      assertEquals(
          ItemNamesException.Code.UNSUPPORTED,
          assertThrows(
                  ItemNamesException.class,
                  () ->
                      opened.project(
                          r -> fail("Opaque record cannot emit a name"), CancellationToken.none()))
              .code());
      assertEquals(1, leases);
    }
    assertEquals(0, leases);
  }

  @Test
  void lateMalformedStringAndTamperedHistoricalManifestDoNotReturnHandles() {
    var decoded = SparseNameSourceFixture.decoded();
    decoded[4096 + SparseNameSourceFixture.record1().length] = (byte) 0xff;
    replace(SparseNameSourceFixture.encode(decoded));
    var rows = new ArrayList<DisplayCandidate>();
    try (var opened = reader().open(request(), CancellationToken.none())) {
      assertEquals(
          ItemNamesException.Code.CORRUPT,
          assertThrows(
                  ItemNamesException.class,
                  () -> opened.project(rows::add, CancellationToken.none()))
              .code());
      assertEquals(1, rows.size());
    }
    replace(SparseNameSourceFixture.encoded());
    PreparedSparseNames original;
    try (var opened = reader().open(request(), CancellationToken.none())) {
      original = opened.project(r -> {}, CancellationToken.none());
    }
    var e = original.evidence();
    var windows = new ArrayList<>(e.windows());
    windows.set(
        0, new ItemProjectionEvidence.DecodedWindow(0, e.windows().getFirst().length() + 1));
    var altered =
        new ItemProjectionEvidence(
            e.association(), e.localVerification(), e.chunks(), windows, Optional.empty());
    var modified = new PreparedSparseNames(original.definition(), altered, original.coverage());
    assertEquals(
        ItemNamesException.Code.CORRUPT,
        assertThrows(
                ItemNamesException.class, () -> reader().verify(modified, CancellationToken.none()))
            .code());
    assertEquals(0, leases);
  }
}
