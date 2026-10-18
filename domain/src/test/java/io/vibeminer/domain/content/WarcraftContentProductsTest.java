package io.vibeminer.domain.content;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WarcraftContentProductsTest {
  @Test
  void reviewedRawIdsShareOnlyTheContentNamespace() {
    for (var product :
        List.of(
            "wow",
            "wowt",
            "wowxptr",
            "wow_beta",
            "wow_classic",
            "wow_classic_ptr",
            "wow_classic_era",
            "wow_classic_era_ptr",
            "wow_anniversary",
            "wow_classic_beta",
            "wow_cn_beta",
            "wowf",
            "wow_classic_titan")) {
      assertTrue(WarcraftContentProducts.usesWowNamespace(product), product);
    }
  }

  @Test
  void prefixesFriendlyNamesCaseAndUnknownProductsDoNotGrantAuthority() {
    assertFalse(WarcraftContentProducts.usesWowNamespace(null));
    for (var product :
        List.of(
            "",
            "wow_unknown",
            "wowdev5",
            "WOW",
            "wow_classic_beta ",
            "World of Warcraft",
            "WoW: Forever",
            "d3",
            "pro")) {
      assertFalse(WarcraftContentProducts.usesWowNamespace(product), product);
    }
  }
}
