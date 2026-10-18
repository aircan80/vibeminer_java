package io.vibeminer.desktop;

import io.vibeminer.application.content.ContentClaimException;
import java.util.Objects;

/** Pure presentation of neutral canonical-content outcomes, without exposing diagnostic text. */
public final class MetadataClaimMessages {
  private MetadataClaimMessages() {}

  public static String heading(ContentClaimException.Code code) {
    return switch (Objects.requireNonNull(code)) {
      case BUSY -> "Metadata representation busy";
      case REPLAN_REQUIRED -> "Metadata plan requires review";
      case RECOVERY_REQUIRED -> "Metadata recovery required";
      case LEGACY_CONFLICT -> "Retained encoding conflict";
      case UNSUPPORTED_REUSE -> "Retained representation cannot be reused";
      case INVALID_REQUEST -> "Exact metadata request invalid";
      case INVALID_STATE -> "Metadata state cannot authorize this action";
      case INTEGRITY_FAILURE -> "Metadata verification failed";
      case MISSING_LOCAL -> "Retained metadata evidence missing";
      case STORAGE_UNAVAILABLE -> "Metadata storage unavailable";
    };
  }

  public static String message(ContentClaimException.Code code) {
    return switch (Objects.requireNonNull(code)) {
      case BUSY ->
          "Canonical metadata content is busy in another operation. Retry after it settles; no"
              + " alternate representation is acquired automatically.";
      case REPLAN_REQUIRED ->
          "The canonical metadata plan is no longer usable. Check requirements again before"
              + " approving acquisition; existing retained representations are not replaced"
              + " automatically.";
      case RECOVERY_REQUIRED ->
          "An earlier metadata publication requires recovery. Inspect retained source evidence"
              + " before retrying; another encoding will not be acquired automatically.";
      case LEGACY_CONFLICT ->
          "Conflicting historical metadata representations require reconciliation. Existing"
              + " memberships and bytes are not deleted or replaced by this request; inspect their"
              + " original source evidence.";
      case UNSUPPORTED_REUSE ->
          "The retained representation lacks supported verification for this metadata workflow."
              + " Inspect its original proof; decoded verification is not upgraded automatically.";
      case INVALID_REQUEST ->
          "The canonical metadata request does not match the required exact source. Check the build"
              + " and requirements again; no automatic repair is performed.";
      case INVALID_STATE ->
          "The canonical metadata state cannot authorize this operation. Inspect retained source"
              + " evidence and recheck requirements; no replacement or capability upgrade is"
              + " inferred.";
      case INTEGRITY_FAILURE ->
          "Metadata bytes or source facts failed integrity verification. Current readable results"
              + " are cleared. Inspect source evidence; retained data is not repaired"
              + " automatically.";
      case MISSING_LOCAL ->
          "Required canonical metadata evidence is missing locally. Current readable results are"
              + " cleared. Inspect the exact retained scope; no network repair starts"
              + " automatically.";
      case STORAGE_UNAVAILABLE ->
          "App-owned canonical metadata storage could not be accessed. Retry the explicit action"
              + " when storage is available; no automatic deletion or replacement is requested.";
    };
  }
}
