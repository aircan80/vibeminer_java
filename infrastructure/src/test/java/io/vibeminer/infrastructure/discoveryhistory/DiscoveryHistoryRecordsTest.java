package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.discoveryhistory.DiscoveryEvent;
import io.vibeminer.application.discoveryhistory.DiscoveryEventSummary;
import io.vibeminer.domain.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiscoveryHistoryRecordsTest {
  private static final Instant NOW = Instant.parse("2026-10-18T12:00:00.123456789Z");
  private static final String BASE = "https://us.version.battle.net/v2/products/wow/";

  static DiscoveryEvent event(int count, int bodySize) {
    var body = new byte[bodySize];
    Arrays.fill(body, (byte) 'x');
    var versions = new SourceDocument(BASE + "versions", NOW.minusSeconds(2), body);
    var cdns = new SourceDocument(BASE + "cdns", NOW.minusSeconds(1), body);
    var columns =
        List.of(
            "Region",
            "BuildConfig",
            "CDNConfig",
            "VersionsName",
            "BuildId",
            "ProductConfig",
            "Future",
            "Empty");
    var values = new LinkedHashMap<String, String>();
    values.put("Region", "us");
    values.put("BuildConfig", "AB".repeat(16));
    values.put("CDNConfig", "CD".repeat(16));
    values.put("VersionsName", "1.2.3");
    values.put("BuildId", "00123");
    values.put("ProductConfig", "EF".repeat(16));
    values.put("Future", "café 未来");
    values.put("Empty", "");
    var evidence = new BuildEvidence("00123", "EF".repeat(16), List.of(versions, cdns));
    var build =
        new BuildSnapshot(
            "wow",
            "World of Warcraft",
            "Published",
            "us",
            "1.2.3",
            "AB".repeat(16),
            "CD".repeat(16),
            NOW,
            false,
            evidence);
    var rows = new ArrayList<DiscoveryEvent.ObservedBuild>();
    for (int i = 0; i < count; i++)
      rows.add(new DiscoveryEvent.ObservedBuild(build, i * 2, values));
    return new DiscoveryEvent(
        UUID.fromString("00000000-0000-0000-0000-000000000092"),
        "wow",
        "us",
        NOW,
        "blizzard-publication-metadata-v1",
        new DiscoveryEvent.ParsedSource(versions, columns, Optional.of("000123")),
        new DiscoveryEvent.ParsedSource(
            cdns, List.of("Name", "Path", "Hosts", "ConfigPath", "Servers"), Optional.empty()),
        new DiscoveryEvent.CdnRow(
            3,
            Map.of(
                "Name",
                "us",
                "Path",
                "tpr/wow",
                "Hosts",
                "cdn.example.test",
                "ConfigPath",
                "",
                "Servers",
                "http://unsupported.test")),
        rows);
  }

  @Test
  void roundtripPreservesRawOrderedFactsAndExactlyTwoSharedEventReceiptObjects() {
    var original = event(64, 1024 * 1024);
    var encoded = DiscoveryHistoryRecords.event(original);
    assertTrue(encoded.length < 65536, "body arrays must not be repeated or embedded");
    var versions = original.versions().source();
    var cdns = original.cdns().source();
    var result = DiscoveryHistoryRecords.event(encoded, versions, cdns);
    assertEquals(original, result);
    assertEquals(
        List.of(
            "Region",
            "BuildConfig",
            "CDNConfig",
            "VersionsName",
            "BuildId",
            "ProductConfig",
            "Future",
            "Empty"),
        result.versions().columns());
    for (var row : result.builds()) {
      assertSame(versions, row.build().evidence().documents().get(0));
      assertSame(cdns, row.build().evidence().documents().get(1));
      assertEquals(NOW, row.build().observedAt());
      assertEquals("AB".repeat(16), row.versionsValues().get("BuildConfig"));
      assertEquals("", row.versionsValues().get("Empty"));
    }
    assertEquals("000123", result.versions().sequence().orElseThrow());
    assertEquals("http://unsupported.test", result.selectedCdn().values().get("Servers"));
    assertEquals(
        result.builds().get(0).build().snapshotId(), result.builds().get(63).build().snapshotId());
  }

  @Test
  void bodyFreeSummaryChecksEntireRecordAndPreservesLocalSequenceAndRawReferences() {
    var original = event(64, 1024 * 1024);
    var bytes = DiscoveryHistoryRecords.event(original);
    assertEquals(
        DiscoveryEventSummary.from(original, 91), DiscoveryHistoryRecords.summary(bytes, 91));
    assertThrows(IllegalArgumentException.class, () -> DiscoveryHistoryRecords.summary(bytes, 0));
    assertThrows(IllegalArgumentException.class, () -> DiscoveryHistoryRecords.summary(null, 1));
    var corrupt = bytes.clone();
    var needle = original.builds().getFirst().build().snapshotId().getBytes(StandardCharsets.UTF_8);
    int offset = indexOf(corrupt, needle);
    assertTrue(offset > 0);
    corrupt[offset] = corrupt[offset] == '0' ? (byte) '1' : (byte) '0';
    assertThrows(
        IllegalArgumentException.class, () -> DiscoveryHistoryRecords.summary(corrupt, 91));
  }

  @Test
  void rejectsNoncanonicalFlagsColumnCountsAndInvalidSourceRowOrdinalsInSummary() {
    var original = event(1, 8);
    var bytes = DiscoveryHistoryRecords.event(original);
    var cursor = ByteBuffer.wrap(bytes);
    for (int i = 0; i < 6; i++) skipText(cursor); // magic, UUID and event scalars
    skipText(cursor);
    skipText(cursor);
    skipText(cursor);
    cursor.getLong(); // versions receipt
    int countOffset = cursor.position();
    int count = cursor.getInt();
    for (int i = 0; i < count; i++) skipText(cursor);
    int flagOffset = cursor.position();
    cursor.get();
    skipText(cursor); // versions sequence
    skipText(cursor);
    skipText(cursor);
    skipText(cursor);
    cursor.getLong(); // CDNs receipt
    count = cursor.getInt();
    for (int i = 0; i < count; i++) skipText(cursor);
    cursor.get(); // absent CDNs sequence
    int ordinalOffset = cursor.position();
    var wrongCount = bytes.clone();
    ByteBuffer.wrap(wrongCount).putInt(countOffset, 65);
    assertThrows(
        IllegalArgumentException.class, () -> DiscoveryHistoryRecords.summary(wrongCount, 1));
    var wrongFlag = bytes.clone();
    wrongFlag[flagOffset] = 2;
    assertThrows(
        IllegalArgumentException.class, () -> DiscoveryHistoryRecords.summary(wrongFlag, 1));
    var wrongOrdinal = bytes.clone();
    ByteBuffer.wrap(wrongOrdinal).putInt(ordinalOffset, 10000);
    assertThrows(
        IllegalArgumentException.class, () -> DiscoveryHistoryRecords.summary(wrongOrdinal, 1));
  }

  private static void skipText(ByteBuffer bytes) {
    bytes.position(bytes.position() + bytes.getInt() + 4);
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }

  @Test
  void encodingBudgetRefusalIsTypedAndMalformedUtf16CannotSilentlyNormalize() {
    var original = event(64, 8);
    // Limits are UTF-16 units, while the codec budget is strict encoded UTF-8 bytes.
    // Legal 4096-unit labels plus legal 16KiB row values can still exceed the event budget.
    var largeLabel = "界".repeat(4096);
    var values = new LinkedHashMap<>(original.builds().getFirst().versionsValues());
    values.put("VersionsName", largeLabel);
    values.put("Future", "界".repeat(12000));
    var rows =
        original.builds().stream()
            .map(
                row -> {
                  var b = row.build();
                  var build =
                      new BuildSnapshot(
                          b.productId(),
                          largeLabel,
                          largeLabel,
                          b.region(),
                          largeLabel,
                          b.buildConfig(),
                          b.cdnConfig(),
                          b.observedAt(),
                          false,
                          b.evidence());
                  return new DiscoveryEvent.ObservedBuild(build, row.versionsRowOrdinal(), values);
                })
            .toList();
    var huge =
        new DiscoveryEvent(
            original.eventId(),
            original.productId(),
            original.region(),
            original.completedAt(),
            original.parserRevision(),
            original.versions(),
            original.cdns(),
            original.selectedCdn(),
            rows);
    assertThrows(
        DiscoveryHistoryRecords.LimitException.class, () -> DiscoveryHistoryRecords.event(huge));
    // Technical labels now reject malformed UTF-16 at the earlier application boundary.
    assertThrows(
        IllegalArgumentException.class, () -> withEdition(event(1, 8), "bad" + (char) 0xd800));
  }

  private static DiscoveryEvent withEdition(DiscoveryEvent event, String label) {
    var builds =
        event.builds().stream()
            .map(
                row -> {
                  var b = row.build();
                  return new DiscoveryEvent.ObservedBuild(
                      new BuildSnapshot(
                          b.productId(),
                          label,
                          b.channel(),
                          b.region(),
                          b.versionLabel(),
                          b.buildConfig(),
                          b.cdnConfig(),
                          b.observedAt(),
                          b.fixture(),
                          b.evidence()),
                      row.versionsRowOrdinal(),
                      row.versionsValues());
                })
            .toList();
    return new DiscoveryEvent(
        event.eventId(),
        event.productId(),
        event.region(),
        event.completedAt(),
        event.parserRevision(),
        event.versions(),
        event.cdns(),
        event.selectedCdn(),
        builds);
  }

  @Test
  void deterministicEncodingUsesSourceColumnsRatherThanMapIteration() {
    var original = event(1, 8);
    var row = original.builds().getFirst();
    var keys = new ArrayList<>(original.versions().columns());
    Collections.reverse(keys);
    var reversed = new LinkedHashMap<String, String>();
    keys.forEach(key -> reversed.put(key, row.versionsValues().get(key)));
    var other =
        new DiscoveryEvent(
            original.eventId(),
            original.productId(),
            original.region(),
            original.completedAt(),
            original.parserRevision(),
            original.versions(),
            original.cdns(),
            original.selectedCdn(),
            List.of(
                new DiscoveryEvent.ObservedBuild(row.build(), row.versionsRowOrdinal(), reversed)));
    assertArrayEquals(
        DiscoveryHistoryRecords.event(original), DiscoveryHistoryRecords.event(other));
  }

  @Test
  void rejectsEveryTruncationTrailingNullOversizeWrongRoleOrChangedReceipt() {
    var original = event(1, 8);
    var bytes = DiscoveryHistoryRecords.event(original);
    for (int i = 0; i < bytes.length; i++) {
      var prefix = Arrays.copyOf(bytes, i);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DiscoveryHistoryRecords.event(
                  prefix, original.versions().source(), original.cdns().source()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DiscoveryHistoryRecords.event(
                Arrays.copyOf(bytes, bytes.length + 1),
                original.versions().source(),
                original.cdns().source()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DiscoveryHistoryRecords.event(
                null, original.versions().source(), original.cdns().source()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DiscoveryHistoryRecords.event(
                new byte[DiscoveryHistoryRecords.MAX_RECORD_BYTES + 1],
                original.versions().source(),
                original.cdns().source()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DiscoveryHistoryRecords.event(
                bytes, original.cdns().source(), original.versions().source()));
    var changed = new SourceDocument(BASE + "versions", NOW, original.versions().source().bytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscoveryHistoryRecords.event(bytes, changed, original.cdns().source()));
    assertThrows(
        IllegalArgumentException.class,
        () -> DiscoveryHistoryRecords.event(bytes, null, original.cdns().source()));
  }

  @Test
  void rejectsInvalidUtf8AndUnboundedTextLengths() {
    var original = event(1, 8);
    var bytes = DiscoveryHistoryRecords.event(original);
    bytes[4] = (byte) 0xff;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DiscoveryHistoryRecords.event(
                bytes, original.versions().source(), original.cdns().source()));
    ByteBuffer.wrap(bytes).putInt(Integer.MAX_VALUE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DiscoveryHistoryRecords.event(
                bytes, original.versions().source(), original.cdns().source()));
  }
}
