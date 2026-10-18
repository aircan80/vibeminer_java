package io.vibeminer.infrastructure.discovery;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.discoveryhistory.*;
import io.vibeminer.domain.SourceDocument;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiscoveryObservationMapperTest {
  static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
  static final String A = "a".repeat(32), B = "b".repeat(32);
  static final String HEADER =
      "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|BuildId!DEC:4|VersionsName!STRING:0|ProductConfig!HEX:16|FutureFlag!STRING:0\n";
  static final String VERSIONS =
      HEADER
          + "## seqn = 000123\n"
          + "eu|"
          + B
          + "|"
          + A
          + "|101|other||EU\n\n# ignored comment\n"
          + "us|"
          + A.toUpperCase(Locale.ROOT)
          + "|"
          + B
          + "|100|same||\n";
  static final String CDNS =
      "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Future!STRING:0\n"
          + "eu|tpr/wow|eu.example.invalid|keep\n"
          + "us|tpr/wow|us.example.invalid|\n"
          + "## seqn = 000009\n";

  static SourceDocument doc(String product, String role, String body) {
    return new SourceDocument(
        "https://us.version.battle.net/v2/products/" + product + "/" + role,
        NOW.minusSeconds(role.equals("versions") ? 2 : 1),
        body.getBytes(StandardCharsets.UTF_8));
  }

  static DiscoveryEvent event() {
    return new DiscoveryObservationMapper()
        .map(
            UUID.randomUUID(),
            "wow",
            "us",
            NOW,
            doc("wow", "versions", VERSIONS),
            doc("wow", "cdns", CDNS));
  }

  @Test
  void retainsParsedOrdinalsAllRawValuesColumnsSequenceAndIndependentReceipts() {
    var e = event();
    assertEquals(1, e.builds().getFirst().versionsRowOrdinal());
    assertEquals(1, e.selectedCdn().rowOrdinal());
    assertEquals(Optional.of("000123"), e.versions().sequence());
    assertEquals(Optional.of("000009"), e.cdns().sequence());
    assertEquals(
        List.of(
            "Region",
            "BuildConfig",
            "CDNConfig",
            "BuildId",
            "VersionsName",
            "ProductConfig",
            "FutureFlag"),
        e.versions().columns());
    assertEquals("", e.builds().getFirst().versionsValues().get("FutureFlag"));
    assertEquals(
        A.toUpperCase(Locale.ROOT), e.builds().getFirst().versionsValues().get("BuildConfig"));
    assertEquals(A, e.builds().getFirst().build().buildConfig());
    assertNotEquals(e.versions().source().capturedAt(), e.cdns().source().capturedAt());
    assertEquals(e, new DiscoveryObservationMapper().replay(e));
  }

  @Test
  void duplicateSnapshotRowsRemainDistinctParsedRowsAndRoutesNeedNotSupportAcquisition() {
    String row = "us|" + A + "|" + B + "|100|same||\n";
    var e =
        new DiscoveryObservationMapper()
            .map(
                UUID.randomUUID(),
                "wow",
                "us",
                NOW,
                doc("wow", "versions", HEADER + row + row),
                doc("wow", "cdns", CDNS));
    assertEquals(2, e.builds().size());
    assertEquals(
        e.builds().getFirst().build().snapshotId(), e.builds().getLast().build().snapshotId());
    assertEquals(0, e.builds().getFirst().versionsRowOrdinal());
    assertEquals(1, e.builds().getLast().versionsRowOrdinal());
    assertFalse(e.cdns().columns().contains("Servers"));
    assertEquals(e, new DiscoveryObservationMapper().replay(e));
  }

  @Test
  void replayRejectsRawUnknownValueTamperingAndUnsupportedParserRevision() {
    var e = event();
    var row = e.builds().getFirst();
    var changed = new HashMap<>(row.versionsValues());
    changed.put("FutureFlag", "forged");
    var forged =
        new DiscoveryEvent(
            e.eventId(),
            e.productId(),
            e.region(),
            e.completedAt(),
            e.parserRevision(),
            e.versions(),
            e.cdns(),
            e.selectedCdn(),
            List.of(
                new DiscoveryEvent.ObservedBuild(row.build(), row.versionsRowOrdinal(), changed)));
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(
                DiscoveryHistoryException.class,
                () -> new DiscoveryObservationMapper().replay(forged))
            .code());
    var unknown =
        new DiscoveryEvent(
            e.eventId(),
            e.productId(),
            e.region(),
            e.completedAt(),
            "future-parser",
            e.versions(),
            e.cdns(),
            e.selectedCdn(),
            e.builds());
    assertEquals(
        DiscoveryHistoryException.Code.UNSUPPORTED_INTERPRETATION,
        assertThrows(
                DiscoveryHistoryException.class,
                () -> new DiscoveryObservationMapper().replay(unknown))
            .code());
  }

  @Test
  void replayChecksRecordedOrdinalsColumnOrderAndRawSequenceWithoutUsingCurrentPointers() {
    var e = event();
    var row = e.builds().getFirst();
    var columns = new ArrayList<>(e.versions().columns());
    Collections.swap(columns, 0, 1);
    var interpretations =
        List.of(
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                e.versions(),
                e.cdns(),
                e.selectedCdn(),
                List.of(new DiscoveryEvent.ObservedBuild(row.build(), 0, row.versionsValues()))),
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                e.versions(),
                e.cdns(),
                new DiscoveryEvent.CdnRow(0, e.selectedCdn().values()),
                e.builds()),
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                new DiscoveryEvent.ParsedSource(
                    e.versions().source(), columns, e.versions().sequence()),
                e.cdns(),
                e.selectedCdn(),
                e.builds()),
            new DiscoveryEvent(
                e.eventId(),
                e.productId(),
                e.region(),
                e.completedAt(),
                e.parserRevision(),
                new DiscoveryEvent.ParsedSource(
                    e.versions().source(), e.versions().columns(), Optional.of("123")),
                e.cdns(),
                e.selectedCdn(),
                e.builds()));
    for (var changed : interpretations)
      assertEquals(
          DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
          assertThrows(
                  DiscoveryHistoryException.class,
                  () -> new DiscoveryObservationMapper().replay(changed))
              .code());
  }

  @Test
  void supportedEventLimitAndMalformedRawValuesFailExplicitlyWithoutPartialSelection() {
    String row = "us|" + A + "|" + B + "|100|same||\n";
    var mapper = new DiscoveryObservationMapper();
    assertEquals(
        DiscoveryHistoryException.Code.LIMIT_EXCEEDED,
        assertThrows(
                DiscoveryHistoryException.class,
                () ->
                    mapper.map(
                        UUID.randomUUID(),
                        "wow",
                        "us",
                        NOW,
                        doc("wow", "versions", HEADER + row.repeat(65)),
                        doc("wow", "cdns", CDNS)))
            .code());
    assertEquals(
        64,
        mapper
            .map(
                UUID.randomUUID(),
                "wow",
                "us",
                NOW,
                doc("wow", "versions", HEADER + row.repeat(64)),
                doc("wow", "cdns", CDNS))
            .builds()
            .size());
    assertEquals(
        DiscoveryHistoryException.Code.CORRUPT_OBSERVATION,
        assertThrows(
                DiscoveryHistoryException.class,
                () ->
                    mapper.map(
                        UUID.randomUUID(),
                        "wow",
                        "us",
                        NOW,
                        doc("wow", "versions", VERSIONS.replace("|100|", "|not-a-build-id|")),
                        doc("wow", "cdns", CDNS)))
            .code());
  }

  @Test
  void exactOfficialRolesRegionAndCdnUniquenessAreRequired() {
    var mapper = new DiscoveryObservationMapper();
    assertThrows(
        RuntimeException.class,
        () ->
            mapper.map(
                UUID.randomUUID(),
                "wow",
                "us",
                NOW,
                doc("wowf", "versions", VERSIONS),
                doc("wow", "cdns", CDNS)));
    assertThrows(
        RuntimeException.class,
        () ->
            mapper.map(
                UUID.randomUUID(),
                "wow",
                "kr",
                NOW,
                doc("wow", "versions", VERSIONS),
                doc("wow", "cdns", CDNS)));
    assertThrows(
        RuntimeException.class,
        () ->
            mapper.map(
                UUID.randomUUID(),
                "wow",
                "us",
                NOW,
                doc("wow", "versions", VERSIONS),
                doc("wow", "cdns", CDNS + "us|tpr/wow|duplicate.invalid|\n")));
  }
}
