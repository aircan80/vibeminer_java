package io.vibeminer.application.buildopening;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;

/**
 * Blocking authority for complete required metadata admission. Replays exact event receipts/route,
 * verifies required whole objects and kind-specific proof, and commits admission/acknowledgement
 * before returning. No success on missing/unknown/unsupported required metadata. Payload
 * enumeration may remain unknown. Cached admission must reverify local evidence without HTTP; no
 * second CAS.
 *
 * <p>Progress is actual bounded observations, including COMPLETE with the exact verified
 * requirement count before successful return. Callbacks stop before actual source/transaction
 * retirement. A committed return wins late cancellation. The gateway owns proof authority and
 * persistence; opaque application descriptors alone authenticate nothing. No concrete adapter is
 * delivered by this port.
 */
@FunctionalInterface
public interface BuildOpeningGateway {
  BuildMetadataAdmission open(
      BuildLibraryReference reference,
      String locale,
      ProgressSink progress,
      CancellationToken cancellation);

  @FunctionalInterface
  interface ProgressSink {
    void update(BuildOpenProgress progress);
  }
}
