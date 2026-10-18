package io.vibeminer.format.wow.schema;

/** Explicit caller scope; no current/latest build is ever inferred. */
public record SchemaSelection(
    String product,
    String build,
    String tableName,
    long tableHash,
    long layoutHash,
    int physicalFieldCount,
    boolean sparse,
    boolean noninlineId) {
  public SchemaSelection {
    if (product == null
        || product.isBlank()
        || build == null
        || !build.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
        || tableName == null
        || !tableName.matches("[A-Za-z][A-Za-z0-9_]*")
        || tableHash < 0
        || tableHash > 0xffffffffL
        || layoutHash < 0
        || layoutHash > 0xffffffffL
        || physicalFieldCount < 0)
      throw new IllegalArgumentException("Invalid explicit schema selection");
  }

  /** Header facts supplied by a separately bounded protocol parser; no body validation implied. */
  public record HeaderFacts(long tableHash, long layoutHash, long fieldCount, int flags) {}
}
