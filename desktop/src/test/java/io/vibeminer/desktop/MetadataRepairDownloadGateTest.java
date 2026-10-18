package io.vibeminer.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.content.MetadataAcquisitionFailure;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import org.junit.jupiter.api.Test;

class MetadataRepairDownloadGateTest {
  private MetadataRepairPresentationModel.State state(
      MetadataAcquisitionFailure.Publication publication) {
    return new MetadataRepairPresentationModel.State(
        null,
        "enUS",
        Kind.ROOT,
        MetadataRepairPresentationModel.Stage.FAILED,
        null,
        null,
        "",
        null,
        null,
        publication);
  }

  @Test
  void uncertaintyBlocksOrdinaryDownloadAndLeavingRestoresOnlyPreviouslyAllowedAction() {
    assertTrue(
        FileLookupPane.ordinaryDownloadDisabled(
            false, state(MetadataAcquisitionFailure.Publication.UNCERTAIN)));
    assertFalse(FileLookupPane.ordinaryDownloadDisabled(false, state(null)));
    assertFalse(FileLookupPane.ordinaryDownloadDisabled(false, null));
    assertFalse(
        FileLookupPane.ordinaryDownloadDisabled(
            false, state(MetadataAcquisitionFailure.Publication.CONCLUSIVELY_UNPUBLISHED)));
    assertTrue(FileLookupPane.ordinaryDownloadDisabled(true, state(null)));
    assertTrue(
        FileLookupPane.ordinaryDownloadDisabled(
            true, state(MetadataAcquisitionFailure.Publication.UNCERTAIN)));
  }
}
