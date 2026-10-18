package io.vibeminer.domain.item;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ItemNameSearchContractsTest {
  @Test
  void strictScalarBoundsPreserveLiteralWhitespaceAndNormalization() {
    for (var invalid : List.of("", "\0", "a\0b", "\ud800", "\udc00", "a\ud800b", "x".repeat(129)))
      assertThrows(IllegalArgumentException.class, () -> new ItemNameLiteralQuery(invalid));
    var supplementary = "\ud83d\ude42".repeat(128);
    assertEquals(512, supplementary.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(supplementary, new ItemNameLiteralQuery(supplementary).text());
    assertThrows(
        IllegalArgumentException.class, () -> new ItemNameLiteralQuery(supplementary + "x"));
    assertEquals("  %_\\' ", new ItemNameLiteralQuery("  %_\\' ").text());
    assertNotEquals(
        new ItemNameLiteralQuery("é").queryId(), new ItemNameLiteralQuery("e\u0301").queryId());
    assertNotEquals(
        new ItemNameLiteralQuery("Name").queryId(), new ItemNameLiteralQuery("name").queryId());
    assertEquals(
        new ItemNameLiteralQuery("🙂").queryId(), new ItemNameLiteralQuery("🙂").queryId());
  }

  @Test
  void literalPredicateMatchesIndependentScalarOracle() {
    var values =
        List.of("", "Authored Name", "αé e\u0301", "🙂%_\\'", "👩‍👩‍👧", " leading trailing ");
    var queries =
        List.of("A", "a", "é", "e\u0301", "\u0301", "🙂", "%_", "\\'", "👩", "‍", " ", " missing");
    for (var value : values)
      for (var text : queries) {
        var query = new ItemNameLiteralQuery(text);
        assertEquals(
            oracle(value.codePoints().toArray(), text.codePoints().toArray()),
            query.matches(candidate(value)),
            value + " query " + text);
      }
    var random = new Random(89);
    int[] scalars = {65, 97, 32, 0xe9, 0x301, 0x3b1, 0x1f642, 0x200d, 37, 95};
    for (int trial = 0; trial < 2000; trial++) {
      var value = new StringBuilder();
      for (int n = random.nextInt(20); n > 0; n--)
        value.appendCodePoint(scalars[random.nextInt(scalars.length)]);
      var query = new StringBuilder();
      for (int n = 1 + random.nextInt(5); n > 0; n--)
        query.appendCodePoint(scalars[random.nextInt(scalars.length)]);
      assertEquals(
          oracle(value.toString().codePoints().toArray(), query.toString().codePoints().toArray()),
          new ItemNameLiteralQuery(query.toString()).matches(candidate(value.toString())));
    }
  }

  @Test
  void cursorBindsActualQueryBothSourceContextsAndFixedRevision() {
    var pair =
        new PreparedItemNames(
            ItemNamesContractsTest.item(Instant.EPOCH),
            ItemNamesContractsTest.names(Instant.EPOCH));
    var query = new ItemNameLiteralQuery("Name");
    var cursor = cursor(pair, query, "a".repeat(64), 0xffffffffL, 25);
    cursor.requireContext(pair, query, 25);
    assertThrows(
        IllegalArgumentException.class,
        () -> cursor.requireContext(pair, new ItemNameLiteralQuery("name"), 25));
    assertThrows(IllegalArgumentException.class, () -> cursor.requireContext(pair, query, 26));
    assertThrows(IllegalArgumentException.class, () -> cursor(pair, query, "invalid", 1, 25));
    assertThrows(IllegalArgumentException.class, () -> cursor(pair, query, "a".repeat(64), 0, 25));
    assertThrows(
        IllegalArgumentException.class,
        () -> cursor(pair, query, "a".repeat(64), 0x100000000L, 25));
    assertNotEquals(cursor, cursor(pair, query, "b".repeat(64), 0xffffffffL, 25));
  }

  @Test
  void domainRetainsFullScalarContractIncludingEditorRestrictedControlsAndStableFraming() {
    String controls = "\r\n\t\u0001\u007f\u0085";
    var query = new ItemNameLiteralQuery(controls);
    assertEquals(controls, query.text());
    assertTrue(query.matches(candidate("before" + controls + "after")));
    assertEquals(
        "bbf20766977d5308b4c577ea9f41df67666b7f1dcd6af028f4ffd9264764d070",
        new ItemNameLiteralQuery(" Name🙂 ").queryId());
    var pair =
        new PreparedItemNames(
            ItemNamesContractsTest.item(Instant.EPOCH),
            ItemNamesContractsTest.names(Instant.EPOCH));
    var q = new ItemNameLiteralQuery("Name");
    var current = cursor(pair, q, "a".repeat(64), 101, 1);
    for (int changed = 0; changed < 6; changed++) {
      var other =
          new ItemNamesSearchCursor(
              changed == 0 ? "0".repeat(64) : current.itemArtifactId(),
              changed == 1 ? "0".repeat(64) : current.namesArtifactId(),
              changed == 2 ? "0".repeat(64) : current.snapshotId(),
              changed == 3 ? "EF1_" + "_".repeat(43) : current.itemSourceRole(),
              changed == 4 ? "EF1_" + "_".repeat(43) : current.namesSourceRole(),
              changed == 5 ? "frFR" : current.locale(),
              q,
              current.queryRevision(),
              current.generationFingerprint(),
              101,
              1);
      assertThrows(IllegalArgumentException.class, () -> other.requireContext(pair, q, 1));
    }
  }

  static ItemNamesSearchCursor cursor(
      PreparedItemNames pair, ItemNameLiteralQuery query, String generation, long id, int size) {
    return new ItemNamesSearchCursor(
        pair.item().artifactId(),
        pair.names().artifactId(),
        pair.snapshotId(),
        pair.item().sourceRole(),
        pair.names().sourceRole(),
        pair.locale(),
        query,
        ItemNamesSearchCursor.QUERY_REVISION,
        generation,
        id,
        size);
  }

  static DisplayCandidate candidate(String value) {
    int bytes = value.getBytes(StandardCharsets.UTF_8).length;
    return new DisplayCandidate(value, 1, 0, 0, 0, bytes + 5, bytes + 5);
  }

  static boolean oracle(int[] value, int[] query) {
    outer:
    for (int start = 0; start <= value.length - query.length; start++) {
      for (int n = 0; n < query.length; n++) if (value[start + n] != query[n]) continue outer;
      return true;
    }
    return false;
  }
}
