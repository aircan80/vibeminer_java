package io.vibeminer.application.buildlibrary;

public record BuildLibraryCursor(
    String queryIdentity,
    long libraryRevision,
    long beforeFirstRegistrationSequence,
    String beforeGroupId,
    int pageSize) {
  public BuildLibraryCursor {
    queryIdentity = BuildLibraryValues.digest(queryIdentity);
    beforeGroupId = BuildLibraryValues.digest(beforeGroupId);
    if (libraryRevision < 0
        || beforeFirstRegistrationSequence < 1
        || pageSize < 1
        || pageSize > 100) throw new IllegalArgumentException("Bounded library cursor required");
  }

  public void requireContext(BuildLibraryQuery query, int size, long revision) {
    if (query == null
        || !query.identity().equals(queryIdentity)
        || size != pageSize
        || revision != libraryRevision)
      throw new IllegalArgumentException("Library cursor query or revision changed");
  }
}
