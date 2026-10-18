package io.vibeminer.domain.content;

import java.util.Set;

/**
 * Reviewed exact product identities sharing Warcraft's immutable content namespace. This mapping
 * does not establish metadata grammar, edition names, availability, or table-decoder support. Every
 * requested source still requires its complete configuration, byte and grammar proofs.
 */
public final class WarcraftContentProducts {
  private static final Set<String> PRODUCTS =
      Set.of(
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
          "wow_classic_titan");

  private WarcraftContentProducts() {}

  /** Exact, case-sensitive raw IDs only; friendly names and prefix guesses grant no authority. */
  public static boolean usesWowNamespace(String productId) {
    return productId != null && PRODUCTS.contains(productId);
  }
}
