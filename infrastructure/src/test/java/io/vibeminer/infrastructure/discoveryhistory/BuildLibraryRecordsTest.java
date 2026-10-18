package io.vibeminer.infrastructure.discoveryhistory;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildlibrary.BuildReferenceGroupKey;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildLibraryRecordsTest {
  private static BuildReferenceGroupKey key(Map<String, String> extra) {
    return new BuildReferenceGroupKey(
        "wow",
        "Published",
        "1.2.3",
        "ab".repeat(16),
        "cd".repeat(16),
        "00123",
        "ef".repeat(16),
        extra);
  }

  @Test
  void roundtripKeepsUnknownCaseEmptyUnicodeAndIdentityWithoutSourceDocuments() {
    var value = key(Map.of("FutureConfig", "AbCd", "futureconfig", "", "Other", "café 未来 🚀"));
    var bytes = BuildLibraryRecords.key(value);
    var restored = BuildLibraryRecords.key(bytes);
    assertEquals(value, restored);
    assertEquals(value.identity(), restored.identity());
    assertEquals("AbCd", restored.additionalReferences().get("FutureConfig"));
    assertEquals("", restored.additionalReferences().get("futureconfig"));
    assertTrue(bytes.length < 512);
  }

  @Test
  void encodingSortsReferenceNamesAndZeroReferencesIsValid() {
    var forward = new LinkedHashMap<String, String>();
    forward.put("Alpha", "a");
    forward.put("Zulu", "z");
    var reverse = new LinkedHashMap<String, String>();
    reverse.put("Zulu", "z");
    reverse.put("Alpha", "a");
    assertArrayEquals(BuildLibraryRecords.key(key(forward)), BuildLibraryRecords.key(key(reverse)));
    assertEquals(key(Map.of()), BuildLibraryRecords.key(BuildLibraryRecords.key(key(Map.of()))));
  }

  @Test
  void rejectsEveryTruncationTrailingNullAndOversize() {
    var bytes = BuildLibraryRecords.key(key(Map.of("Future", "value")));
    for (int i = 0; i < bytes.length; i++) {
      var prefix = Arrays.copyOf(bytes, i);
      assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(prefix));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildLibraryRecords.key(Arrays.copyOf(bytes, bytes.length + 1)));
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key((byte[]) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildLibraryRecords.key((BuildReferenceGroupKey) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildLibraryRecords.key(new byte[BuildLibraryRecords.MAX_KEY_BYTES + 1]));
  }

  @Test
  void rejectsCorruptIdentityUtf8LengthsCountAndNoncanonicalReferenceOrder() {
    var bytes = BuildLibraryRecords.key(key(Map.of("Alpha", "a", "Bravo", "b")));
    var identity = bytes.clone();
    identity[13] ^= 1;
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(identity));
    var utf8 = bytes.clone();
    utf8[4] = (byte) 0xff;
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(utf8));
    var length = bytes.clone();
    ByteBuffer.wrap(length).putInt(Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(length));
    var cursor = ByteBuffer.wrap(bytes);
    for (int i = 0; i < 9; i++) skip(cursor); // magic, identity, seven scalar fields
    int countOffset = cursor.position();
    var count = bytes.clone();
    ByteBuffer.wrap(count).putInt(countOffset, 65);
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(count));
    cursor.getInt();
    int first = cursor.position();
    skip(cursor);
    skip(cursor);
    int second = cursor.position();
    skip(cursor);
    skip(cursor);
    var order = bytes.clone();
    System.arraycopy(bytes, second, order, first, cursor.position() - second);
    System.arraycopy(bytes, first, order, second, second - first);
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(order));
    var duplicate = bytes.clone();
    System.arraycopy(bytes, first, duplicate, second, second - first);
    assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(duplicate));
  }

  @Test
  void decodingCannotNormalizeProtocolHexOrProductConfig() {
    var original = BuildLibraryRecords.key(key(Map.of()));
    var cursor = ByteBuffer.wrap(original);
    for (int i = 0; i < 5; i++) skip(cursor); // magic, identity, product, channel, version
    int buildConfig = cursor.position() + 4;
    skip(cursor);
    int cdnConfig = cursor.position() + 4;
    skip(cursor);
    skip(cursor);
    int productConfig = cursor.position() + 4;
    for (int offset : new int[] {buildConfig, cdnConfig, productConfig}) {
      var bytes = original.clone();
      bytes[offset] = (byte) Character.toUpperCase((char) bytes[offset]);
      // The stored identity still matches after normalization: reject the raw change itself.
      assertThrows(IllegalArgumentException.class, () -> BuildLibraryRecords.key(bytes));
    }
  }

  @Test
  void maximumNamesAndMultibyteValuesRemainWithinExplicitBudget() {
    var extra = new LinkedHashMap<String, String>();
    for (int i = 0; i < 64; i++)
      extra.put("Field" + i + "x".repeat(16370), i == 0 ? "界".repeat(16384) : "");
    var value = key(extra);
    var bytes = BuildLibraryRecords.key(value);
    assertTrue(bytes.length < BuildLibraryRecords.MAX_KEY_BYTES);
    assertEquals(value, BuildLibraryRecords.key(bytes));
  }

  private static void skip(ByteBuffer bytes) {
    int size = bytes.getInt();
    bytes.position(bytes.position() + size);
  }
}
