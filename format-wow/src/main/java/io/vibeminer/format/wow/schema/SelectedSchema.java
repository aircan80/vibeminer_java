package io.vibeminer.format.wow.schema;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** MATCH is a compatible community schema candidate, never validated rows or field semantics. */
public record SelectedSchema(
    Status status,
    SchemaSelection selection,
    Provenance provenance,
    List<Field> fields,
    List<String> diagnostics) {
  public SelectedSchema {
    fields = List.copyOf(fields);
    diagnostics = List.copyOf(diagnostics);
  }

  public enum Status {
    MATCH,
    MISSING,
    AMBIGUOUS,
    INCOMPATIBLE
  }

  public enum Type {
    INT,
    FLOAT,
    STRING,
    LOCSTRING
  }

  public enum Sign {
    SIGNED,
    UNSIGNED,
    NOT_SPECIFIED
  }

  public record Relation(String table, String field) {}

  public record Field(
      String name,
      String rawColumnName,
      Type type,
      boolean unverified,
      Optional<Relation> relation,
      int integerBits,
      Sign sign,
      int arrayLength,
      Set<String> annotations) {
    public Field {
      annotations = Set.copyOf(annotations);
    }

    public boolean noninline() {
      return annotations.contains("noninline");
    }

    public boolean id() {
      return annotations.contains("id");
    }
  }

  public record Provenance(
      String provider,
      String revision,
      String sourceUrl,
      String sha256,
      String license,
      String attribution,
      boolean modified) {
    public Provenance {
      for (String value : new String[] {provider, revision, sourceUrl, license, attribution})
        if (value == null || value.isBlank())
          throw new IllegalArgumentException("Missing schema provenance");
      if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid schema digest");
    }
  }
}
