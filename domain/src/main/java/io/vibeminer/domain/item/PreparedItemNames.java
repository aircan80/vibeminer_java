package io.vibeminer.domain.item;

import java.util.Objects;

/** Explicit exact-context pair; each side retains independent physical/proof/coverage facts. */
public record PreparedItemNames(PreparedItemProjection item, PreparedSparseNames names) {
  public PreparedItemNames {
    Objects.requireNonNull(item);
    Objects.requireNonNull(names);
    var v = item.evidence().association().descriptor().association();
    var b = v.build();
    var i = item.definition().interpretation();
    var s = i.schema();
    if (!item.snapshotId().equals(names.snapshotId())
        || !item.locale().equals(names.locale())
        || !b.productId().equals("wow")
        || !b.region().equals("us")
        || !b.versionLabel().equals("12.1.5.70448")
        || v.fileDataId() != 841626
        || !v.contentKey().equals("1df6b6dbae36878cea020a62b15b9bd6")
        || i.tableHash() != 0x50238ec2L
        || i.layoutHash() != 0x996192aaL
        || i.sectionOrdinal() != 0
        || item.definition().sources().size() != 1
        || !s.provider().equals("WoWDBDefs")
        || !s.revision().equals("b9a63096cdd99b26967f17f9515377ae62cadfd7")
        || !s.definitionPath().equals("definitions/Item.dbd")
        || !s.definitionSha256()
            .equals("076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120")
        || !s.fields()
            .equals(
                java.util.List.of(
                    new ItemArtifactDefinition.FieldBinding(
                        ItemArtifactDefinition.Field.CLASS_ID,
                        0,
                        32,
                        ItemArtifactDefinition.Sign.SIGNED),
                    new ItemArtifactDefinition.FieldBinding(
                        ItemArtifactDefinition.Field.SUBCLASS_ID,
                        1,
                        8,
                        ItemArtifactDefinition.Sign.UNSIGNED),
                    new ItemArtifactDefinition.FieldBinding(
                        ItemArtifactDefinition.Field.INVENTORY_TYPE,
                        3,
                        8,
                        ItemArtifactDefinition.Sign.SIGNED),
                    new ItemArtifactDefinition.FieldBinding(
                        ItemArtifactDefinition.Field.ICON_FILE_DATA_ID,
                        6,
                        32,
                        ItemArtifactDefinition.Sign.SIGNED))))
      throw new IllegalArgumentException(
          "Item/name pair requires the same exact admitted snapshot/locale");
  }

  public String snapshotId() {
    return item.snapshotId();
  }

  public String locale() {
    return item.locale();
  }

  public void requireRow(NamedItemRow row) {
    Objects.requireNonNull(row);
    var physical = row.item();
    if (physical.sectionOrdinal() != item.coverage().sectionOrdinal()
        || physical.physicalOrdinal() >= item.coverage().physicalRows())
      throw new IllegalArgumentException("Item row lies outside paired physical scope");
    for (int n = 0; n < 4; n++) {
      var value = physical.values().get(n);
      var binding = item.definition().interpretation().schema().fields().get(n);
      if (value.field() != binding.field()
          || value.candidateBits() != binding.candidateBits()
          || value.candidateSign() != binding.sign())
        throw new IllegalArgumentException(
            "Item candidate binding differs from paired interpretation");
    }
    names.requireMatch(row.name());
  }

  public void requireLookup(JoinedItemLookup lookup) {
    Objects.requireNonNull(lookup);
    if (!lookup.item().coverage().equals(item.coverage()))
      throw new IllegalArgumentException("Joined lookup changed Item coverage");
    if (lookup.item().outcome() == ItemLookupResult.Outcome.COPY_UNINDEXED
        && item.coverage().unindexedCopyDeclarations() == 0)
      throw new IllegalArgumentException("Item copy result lacks admitted declarations");
    if (lookup.name().isPresent())
      requireRow(new NamedItemRow(lookup.item().row().orElseThrow(), lookup.name().get()));
  }
}
