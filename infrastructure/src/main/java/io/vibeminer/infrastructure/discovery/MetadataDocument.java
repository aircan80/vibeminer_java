package io.vibeminer.infrastructure.discovery;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parsed source metadata; unknown columns and publication flags remain intact. */
public record MetadataDocument(List<String> columns, List<Map<String, String>> rows, Optional<String> sequence) {
    public MetadataDocument {
        columns = List.copyOf(columns);
        rows = rows.stream().map(Map::copyOf).toList();
        sequence = sequence == null ? Optional.empty() : sequence;
    }

    public void requireColumns(String... required) {
        for (var column : required) {
            if (!columns.contains(column)) throw new MetadataFormatException("Missing required metadata column: " + column);
        }
    }
}
