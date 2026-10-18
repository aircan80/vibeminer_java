package io.vibeminer.infrastructure.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.content.EncodedContentProof;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import org.junit.jupiter.api.Test;

class CanonicalEncodedCompatibilityTest {
  @Test
  void neutralCodecPreservesExistingEncodedRecordBytesAndProof() throws Exception {
    var receipt =
        SqliteSelectedFileCatalogTest.prospective(
            SqliteSelectedFileCatalogTest.slot(),
            SqliteSelectedFileCatalogTest.A,
            "fixture".getBytes(),
            "us.cdn.blizzard.com");
    byte[] existing = SelectedFileRecords.representation(receipt);
    var read = CanonicalContentRecords.decode(existing);
    assertEquals(new EncodedContentProof(receipt), read);
    assertArrayEquals(existing, CanonicalContentRecords.encode(read));
  }
}
