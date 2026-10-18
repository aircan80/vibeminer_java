package io.vibeminer.domain.item;

import java.util.*;

/** Item record ID and physical values, never an Item table FileDataID or a fabricated copy row. */
public record ItemPhysicalRow(
    long recordId, int physicalOrdinal, int sectionOrdinal, List<IntegerCandidate> values) {
  public enum PhysicalInterpretation {
    UNSIGNED_BITS,
    SIGNED_TWOS_COMPLEMENT
  }

  public record IntegerCandidate(
      ItemArtifactDefinition.Field field,
      long rawBits,
      int rawWidth,
      PhysicalInterpretation physicalInterpretation,
      long candidateValue,
      int candidateBits,
      ItemArtifactDefinition.Sign candidateSign) {
    public IntegerCandidate {
      Objects.requireNonNull(field);
      Objects.requireNonNull(physicalInterpretation);
      Objects.requireNonNull(candidateSign);
      if (rawWidth < 1
          || rawWidth > 64
          || candidateBits < 1
          || candidateBits > 64
          || rawWidth < 64 && (rawBits >>> rawWidth) != 0)
        throw new IllegalArgumentException("Invalid raw integer width/bits");
      if (candidateBits < 64) {
        int shift = 64 - candidateBits;
        if (candidateSign == ItemArtifactDefinition.Sign.SIGNED
                && ((candidateValue << shift) >> shift) != candidateValue
            || candidateSign == ItemArtifactDefinition.Sign.UNSIGNED
                && (candidateValue < 0 || (candidateValue >>> candidateBits) != 0))
          throw new IllegalArgumentException(
              "Candidate integer exceeds its declared interpretation");
      }
    }
  }

  public ItemPhysicalRow {
    ItemValues.id(recordId);
    if (physicalOrdinal < 0 || sectionOrdinal < 0)
      throw new IllegalArgumentException("Physical coordinates required");
    values = List.copyOf(values);
    if (values.size() != 4)
      throw new IllegalArgumentException("Exactly four integer candidates required");
    for (int i = 0; i < 4; i++)
      if (values.get(i).field() != ItemArtifactDefinition.Field.values()[i])
        throw new IllegalArgumentException("Candidate fields must retain fixed order");
  }
}
