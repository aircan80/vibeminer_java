package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.CancellationToken;
import java.util.Optional;

/** Authoritative body-free SQL projection; no current physical readiness or network claim. */
public interface BuildLibraryReader {
  /**
   * Revision includes changes to all query facts, including Local sources; stale cursor is
   * explicit.
   */
  BuildLibraryPage page(
      BuildLibraryQuery query,
      Optional<BuildLibraryCursor> cursor,
      int pageSize,
      CancellationToken token);

  /** Re-read exact group; never substitute another group or expose arbitrary config lookup. */
  BuildLibraryPage.Entry select(String groupId, CancellationToken token);
}
