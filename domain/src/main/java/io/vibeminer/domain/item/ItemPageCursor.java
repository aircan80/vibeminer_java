package io.vibeminer.domain.item;

/**
 * Fixed numeric keyset cursor, bound to artifact and exact consumer context rather than a row
 * index.
 */
public record ItemPageCursor(
    String artifactId,
    String snapshotId,
    String sourceRole,
    String locale,
    String queryRevision,
    long afterRecordId,
    int pageSize) {
  public static final String QUERY_REVISION = "item-id-ascending-v1";

  public ItemPageCursor {
    artifactId = ItemValues.digest(artifactId);
    snapshotId = ItemValues.digest(snapshotId);
    locale = ItemValues.locale(locale);
    ItemValues.id(afterRecordId);
    if (sourceRole == null
        || !sourceRole.matches("EF1_[A-Za-z0-9_-]{43}")
        || !QUERY_REVISION.equals(queryRevision)
        || pageSize < 1
        || pageSize > 500) throw new IllegalArgumentException("Invalid fixed-query cursor context");
  }

  public void requireContext(PreparedItemProjection handle, int requestedPageSize) {
    if (!artifactId.equals(handle.artifactId())
        || !snapshotId.equals(handle.snapshotId())
        || !sourceRole.equals(handle.sourceRole())
        || !locale.equals(handle.locale())
        || requestedPageSize != pageSize)
      throw new IllegalArgumentException(
          "Cursor belongs to another artifact/consumer/query context");
  }
}
