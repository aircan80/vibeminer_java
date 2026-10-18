package io.vibeminer.desktop;

import java.util.List;

/** Versioned presentation aliases, never endpoint rewrites or decoder/availability assertions. */
final class WarcraftProductLabels {
  static final String REVISION = "warcraft-product-aliases-2026-10-18-v1";
  private static final List<GuidedDiscoveryPane.ProductOption> OPTIONS =
      List.of(
          option("wow", "World of Warcraft · Retail"),
          option("wowt", "World of Warcraft · PTR"),
          option("wowxptr", "World of Warcraft · PTR · Alternate source"),
          option("wow_beta", "World of Warcraft · Beta"),
          option("wow_classic", "WoW Classic · Progression"),
          option("wow_classic_ptr", "WoW Classic · PTR"),
          option("wow_classic_era", "WoW Classic Era"),
          option("wow_classic_era_ptr", "WoW Classic Era · PTR"),
          option("wow_anniversary", "WoW Classic · Anniversary"),
          option("wow_classic_beta", "WoW: Forever · Test source (community label)"),
          option("wow_cn_beta", "WoW: Forever · Beta (community label)"),
          option("wowf", "WoW: Forever · Stress Test (community label)"),
          option("wow_classic_titan", "WoW Classic · Titan Reforged (community label)"));

  private WarcraftProductLabels() {}

  static List<GuidedDiscoveryPane.ProductOption> options() {
    return OPTIONS;
  }

  static String attribution() {
    return "Presentation aliases from wowlib / BlizzTrack, reviewed 18 Oct 2026. Raw IDs remain"
        + " authoritative; aliases do not establish availability or format support.";
  }

  static String sources() {
    return REVISION
        + "\n"
        + attribution()
        + "\n"
        + "https://skarndev.github.io/wowlib/python/core/\n"
        + "https://blizztrack.com/en\n"
        + "Friendly labels do not replace the published source channel or configuration identity.";
  }

  private static GuidedDiscoveryPane.ProductOption option(String id, String description) {
    return new GuidedDiscoveryPane.ProductOption(id, description);
  }
}
