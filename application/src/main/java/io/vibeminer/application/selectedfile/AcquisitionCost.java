package io.vibeminer.application.selectedfile;

/** Known encoded body cost and bounded additional physical-space estimate; no decoded claim. */
public record AcquisitionCost(long encodedTransferBytes, long maximumAdditionalPhysicalBytes) {
  public AcquisitionCost {
    if (encodedTransferBytes < 0 || maximumAdditionalPhysicalBytes < 0)
      throw new IllegalArgumentException("Nonnegative acquisition costs required");
  }
}
