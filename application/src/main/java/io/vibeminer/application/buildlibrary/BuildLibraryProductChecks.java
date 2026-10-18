package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.CancellationToken;
import java.time.Instant;
import java.util.List;

/** Tiny product-before-network extension; same SQL authority/global counter as regional checks. */
public interface BuildLibraryProductChecks {
  BuildLibraryProductCheckTicket beginProductCheck(
      String productId, Instant requestedAt, CancellationToken token);

  /** Stable parent+region ticket inherits parent's original ordinal, even if derived late. */
  BuildLibraryCheckTicket deriveRegionalCheck(
      BuildLibraryProductCheckTicket parent, String region, CancellationToken token);

  /** Previous durable scopes to mark UNKNOWN on whole-product failure; no guessed region list. */
  List<BuildLibraryScope> knownScopes(String productId, CancellationToken token);
}
