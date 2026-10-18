package io.vibeminer.domain.item;

import java.util.Objects;

/** Literal search position bound to the full pair, actual query and opaque artifact generations. */
public record ItemNamesSearchCursor(
    String itemArtifactId,
    String namesArtifactId,
    String snapshotId,
    String itemSourceRole,
    String namesSourceRole,
    String locale,
    ItemNameLiteralQuery query,
    String queryRevision,
    String generationFingerprint,
    long afterRecordId,
    int pageSize) {
  public static final String QUERY_REVISION = "physical-item-literal-display-id-ascending-v1";

  public ItemNamesSearchCursor {
    itemArtifactId = ItemValues.digest(itemArtifactId);
    namesArtifactId = ItemValues.digest(namesArtifactId);
    snapshotId = ItemValues.digest(snapshotId);
    generationFingerprint = ItemValues.digest(generationFingerprint);
    locale = ItemValues.locale(locale);
    Objects.requireNonNull(query);
    ItemValues.id(afterRecordId);
    if (afterRecordId == 0
        || itemSourceRole == null
        || !itemSourceRole.matches("EF1_[A-Za-z0-9_-]{43}")
        || namesSourceRole == null
        || !namesSourceRole.matches("EF1_[A-Za-z0-9_-]{43}")
        || !QUERY_REVISION.equals(queryRevision)
        || pageSize < 1
        || pageSize > 500)
      throw new IllegalArgumentException("Invalid exact literal-search cursor");
  }

  public void requireContext(
      PreparedItemNames pair, ItemNameLiteralQuery requested, int requestedPageSize) {
    if (!itemArtifactId.equals(pair.item().artifactId())
        || !namesArtifactId.equals(pair.names().artifactId())
        || !snapshotId.equals(pair.snapshotId())
        || !itemSourceRole.equals(pair.item().sourceRole())
        || !namesSourceRole.equals(pair.names().sourceRole())
        || !locale.equals(pair.locale())
        || !query.equals(requested)
        || pageSize != requestedPageSize)
      throw new IllegalArgumentException(
          "Cursor belongs to another exact pair, query or page size");
  }
}
