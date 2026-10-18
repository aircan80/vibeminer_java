package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class WarcraftProductLabelsTest {
  @Test
  void reviewedCodesRemainDistinctWithoutAliasEndpointRewrites() {
    var ids =
        WarcraftProductLabels.options().stream()
            .map(GuidedDiscoveryPane.ProductOption::productId)
            .toList();
    assertEquals(ids.size(), new HashSet<>(ids).size());
    assertTrue(
        ids.containsAll(
            java.util.List.of(
                "wow", "wowt", "wowxptr", "wow_beta", "wow_classic_beta", "wow_cn_beta", "wowf")));
    assertFalse(ids.contains("wow_ptr"));
    assertFalse(ids.contains("wow_forever"));
    assertFalse(ids.contains("wow_unreviewed"));
    assertFalse(ids.contains("d4"));
  }

  @Test
  void mutableCommunityNamesRemainAttributedAndDoNotFreezeCurrentExpansion() {
    var options = WarcraftProductLabels.options();
    assertTrue(
        options.stream()
            .filter(o -> o.productId().equals("wow_classic_beta"))
            .findFirst()
            .orElseThrow()
            .description()
            .contains("community label"));
    assertTrue(
        options.stream()
            .filter(o -> o.productId().equals("wow_classic"))
            .findFirst()
            .orElseThrow()
            .description()
            .contains("Progression"));
    assertTrue(WarcraftProductLabels.sources().contains("2026-10-18-v1"));
    assertTrue(WarcraftProductLabels.attribution().contains("do not establish availability"));
    assertThrows(UnsupportedOperationException.class, () -> options.clear());
  }
}
