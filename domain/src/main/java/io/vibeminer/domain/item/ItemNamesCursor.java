package io.vibeminer.domain.item;

/** Fixed Item-ID keyset cursor bound to both artifacts and source memberships. */
public record ItemNamesCursor(
    String itemArtifactId,
    String namesArtifactId,
    String snapshotId,
    String itemSourceRole,
    String namesSourceRole,
    String locale,
    String queryRevision,
    String optionsRevision,
    long afterRecordId,
    int pageSize) {
  public static final String QUERY_REVISION = "item-names-id-ascending-v1";
  public static final String OPTIONS_REVISION = "physical-item-left-join-physical-names-v1";

  public ItemNamesCursor {
    itemArtifactId = ItemValues.digest(itemArtifactId);
    namesArtifactId = ItemValues.digest(namesArtifactId);
    snapshotId = ItemValues.digest(snapshotId);
    locale = ItemValues.locale(locale);
    ItemValues.id(afterRecordId);
    if (afterRecordId == 0
        || itemSourceRole == null
        || !itemSourceRole.matches("EF1_[A-Za-z0-9_-]{43}")
        || namesSourceRole == null
        || !namesSourceRole.matches("EF1_[A-Za-z0-9_-]{43}")
        || !QUERY_REVISION.equals(queryRevision)
        || !OPTIONS_REVISION.equals(optionsRevision)
        || pageSize < 1
        || pageSize > 500) throw new IllegalArgumentException("Invalid paired fixed-query cursor");
  }

  public void requireContext(PreparedItemNames pair, int requestedPageSize) {
    if (!itemArtifactId.equals(pair.item().artifactId())
        || !namesArtifactId.equals(pair.names().artifactId())
        || !snapshotId.equals(pair.snapshotId())
        || !itemSourceRole.equals(pair.item().sourceRole())
        || !namesSourceRole.equals(pair.names().sourceRole())
        || !locale.equals(pair.locale())
        || pageSize != requestedPageSize)
      throw new IllegalArgumentException(
          "Cursor belongs to another exact source pair/query context");
  }
}
