package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MetadataStreamBudgetsTest {
  @Test
  void costIncludesBothDecodePassesAndEncodingPageWalks() {
    assertEquals(400, MetadataStreamBudgets.requiredDecodeWork(200));
    assertEquals(
        400,
        MetadataStreamBudgets.requiredGrammarWork(
            io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ENCODING, 200));
    assertEquals(
        200,
        MetadataStreamBudgets.requiredGrammarWork(
            io.vibeminer.domain.content.MetadataStructureAttestation.Kind.DOWNLOAD, 200));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataStreamBudgets.requiredDecodeWork(Long.MAX_VALUE));
  }
}
