package io.vibeminer.format.wow.schema;

import static io.vibeminer.format.wow.schema.SchemaException.Reason.*;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Narrow bounded selector/field grammar, based on pinned WoWDBDefs README DBD format. */
public final class SelectedDbdParser {
  private static final Pattern COLUMN =
      Pattern.compile(
          "(int|float|string|locstring)(?:<([A-Za-z0-9_]+)::([A-Za-z0-9_]+)>)?"
              + " ([A-Za-z_][A-Za-z0-9_]*)(\\?)?");
  private static final Pattern FIELD =
      Pattern.compile(
          "(?:\\$([^$]+)\\$)?([A-Za-z_][A-Za-z0-9_]*)(?:<(u?)([0-9]+)>)?(?:\\[([0-9]+)\\])?");

  public record Limits(
      int maxBytes,
      int maxLines,
      int maxLineCharacters,
      int maxColumns,
      int maxBlocks,
      int maxArrayLength) {
    public Limits {
      if (maxBytes < 1
          || maxLines < 1
          || maxLineCharacters < 1
          || maxColumns < 1
          || maxBlocks < 1
          || maxArrayLength < 1) throw new IllegalArgumentException("Invalid DBD bounds");
    }
  }

  private record Column(
      String name,
      String rawName,
      SelectedSchema.Type type,
      boolean unverified,
      Optional<SelectedSchema.Relation> relation) {}

  public SelectedSchema parse(
      byte[] bytes,
      SchemaSelection selected,
      SchemaSelection.HeaderFacts header,
      SelectedSchema.Provenance source,
      Limits limits,
      BooleanSupplier cancelled)
      throws SchemaException {
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(selected);
    Objects.requireNonNull(header);
    Objects.requireNonNull(source);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    if (bytes.length > limits.maxBytes()) throw error(LIMIT, "DBD source exceeds byte bound");
    if (!digest(bytes).equals(source.sha256()))
      throw error(MALFORMED, "DBD source digest differs from pinned provenance");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException invalid) {
      throw error(MALFORMED, "DBD source is not strict UTF-8");
    }
    if (text.indexOf('\0') >= 0) throw error(MALFORMED, "DBD source contains NUL");
    String[] lines = text.split("\\n", -1);
    if (lines.length > limits.maxLines()) throw error(LIMIT, "DBD source exceeds line bound");
    var groups = new ArrayList<List<String>>();
    var group = new ArrayList<String>();
    for (String raw : lines) {
      check(cancelled);
      if (raw.length() > limits.maxLineCharacters())
        throw error(LIMIT, "DBD line exceeds character bound");
      if (raw.trim().startsWith("//")) continue;
      String line = raw.split("//", 2)[0].trim();
      if (line.isEmpty()) {
        if (!group.isEmpty()) {
          groups.add(List.copyOf(group));
          group.clear();
        }
      } else group.add(line);
    }
    if (!group.isEmpty()) groups.add(List.copyOf(group));
    if (groups.isEmpty() || !groups.getFirst().getFirst().equals("COLUMNS"))
      throw error(MALFORMED, "DBD requires initial COLUMNS group");
    if (groups.size() - 1 > limits.maxBlocks())
      throw error(LIMIT, "DBD source exceeds layout block bound");
    var columns = new LinkedHashMap<String, Column>();
    for (String line : groups.getFirst().subList(1, groups.getFirst().size())) {
      check(cancelled);
      Matcher m = COLUMN.matcher(line);
      if (!m.matches()) throw error(UNSUPPORTED, "Unsupported DBD column declaration");
      var relation =
          m.group(2) == null
              ? Optional.<SelectedSchema.Relation>empty()
              : Optional.of(new SelectedSchema.Relation(m.group(2), m.group(3)));
      var column =
          new Column(
              m.group(4),
              m.group(4) + (m.group(5) == null ? "" : "?"),
              SelectedSchema.Type.valueOf(m.group(1).toUpperCase(Locale.ROOT)),
              m.group(5) != null,
              relation);
      if (columns.putIfAbsent(column.name(), column) != null)
        throw error(MALFORMED, "Duplicate DBD column");
      if (columns.size() > limits.maxColumns())
        throw error(LIMIT, "DBD column count exceeds bound");
    }
    var matches = new ArrayList<List<String>>();
    for (var block : groups.subList(1, groups.size())) {
      check(cancelled);
      boolean layout = false, build = false;
      int layoutLines = 0;
      for (String line : block) {
        check(cancelled);
        if (line.startsWith("LAYOUT ")) {
          if (++layoutLines > 1) throw error(MALFORMED, "Repeated LAYOUT selector");
          for (String hash : line.substring(7).split(",", -1)) {
            String value = hash.trim();
            if (!value.matches("[0-9A-Fa-f]{8}"))
              throw error(MALFORMED, "Invalid DBD layout selector");
            layout |= Long.parseLong(value, 16) == selected.layoutHash();
          }
        } else if (line.startsWith("BUILD ")) {
          for (String token : line.substring(6).split(",", -1))
            build |= matchesBuild(token.trim(), selected.build());
        }
      }
      if (layout && build) matches.add(block);
    }
    if (matches.isEmpty())
      return result(
          SelectedSchema.Status.MISSING,
          selected,
          source,
          List.of(),
          "No exact build/layout selector");
    if (matches.size() > 1)
      return result(
          SelectedSchema.Status.AMBIGUOUS,
          selected,
          source,
          List.of(),
          "Multiple exact build/layout selectors");
    var fields = new ArrayList<SelectedSchema.Field>();
    var names = new HashSet<String>();
    for (String line : matches.getFirst()) {
      check(cancelled);
      if (line.startsWith("LAYOUT ") || line.startsWith("BUILD ")) continue;
      // COMMENT has human-only DBD semantics. Sparse compatibility comes solely from
      // the caller's explicit cohort profile and independently observed header flags.
      if (line.startsWith("COMMENT ")) continue;
      Matcher m = FIELD.matcher(line);
      if (!m.matches()) throw error(UNSUPPORTED, "Unsupported selected DBD field grammar");
      Column column = columns.get(m.group(2));
      if (column == null || !names.add(column.name()))
        throw error(MALFORMED, "Undefined or repeated selected DBD field");
      Set<String> annotations = new LinkedHashSet<>();
      if (m.group(1) != null)
        for (String annotation : m.group(1).split(",", -1)) {
          if (!Set.of("noninline", "id", "relation").contains(annotation)
              || !annotations.add(annotation))
            throw error(UNSUPPORTED, "Unsupported selected DBD annotation");
        }
      int bits = m.group(4) == null ? 0 : number(m.group(4));
      if (m.group(4) != null
          && (column.type() != SelectedSchema.Type.INT || !Set.of(8, 16, 32, 64).contains(bits)))
        throw error(UNSUPPORTED, "Unsupported selected DBD field width");
      int array = m.group(5) == null ? 1 : number(m.group(5));
      if (array < 1) throw error(MALFORMED, "Selected DBD array must be nonempty");
      if (array > limits.maxArrayLength()) throw error(LIMIT, "Selected DBD array exceeds bound");
      var sign =
          bits == 0
              ? SelectedSchema.Sign.NOT_SPECIFIED
              : (m.group(3).equals("u")
                  ? SelectedSchema.Sign.UNSIGNED
                  : SelectedSchema.Sign.SIGNED);
      fields.add(
          new SelectedSchema.Field(
              column.name(),
              column.rawName(),
              column.type(),
              column.unverified(),
              column.relation(),
              bits,
              sign,
              array,
              annotations));
      if (fields.size() > limits.maxColumns())
        throw error(LIMIT, "Selected DBD fields exceed bound");
    }
    long inline = fields.stream().filter(f -> !f.noninline()).count();
    var ids = fields.stream().filter(SelectedSchema.Field::id).toList();
    boolean validId =
        ids.size() == 1
            && ids.getFirst().noninline() == selected.noninlineId()
            && ids.getFirst().type() == SelectedSchema.Type.INT
            && ids.getFirst().arrayLength() == 1
            && ids.getFirst().integerBits() == 32;
    boolean compatible =
        header.flags() >= 0
            && header.flags() <= 0xffff
            && selected.product().equals("wow")
            && inline == selected.physicalFieldCount()
            && validId
            && header.tableHash() == selected.tableHash()
            && header.layoutHash() == selected.layoutHash()
            && header.fieldCount() == selected.physicalFieldCount()
            && ((header.flags() & 1) != 0) == selected.sparse()
            && ((header.flags() & 4) != 0) == selected.noninlineId();
    check(cancelled);
    return result(
        compatible ? SelectedSchema.Status.MATCH : SelectedSchema.Status.INCOMPATIBLE,
        selected,
        source,
        fields,
        compatible
            ? "Compatible community schema candidate; rows and semantic interpretation unverified"
            : "Explicit product/header/count/noninline/sparse facts disagree");
  }

  private static SelectedSchema result(
      SelectedSchema.Status status,
      SchemaSelection selected,
      SelectedSchema.Provenance source,
      List<SelectedSchema.Field> fields,
      String diagnostic) {
    return new SelectedSchema(status, selected, source, fields, List.of(diagnostic));
  }

  private static int number(String value) throws SchemaException {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      throw error(LIMIT, "DBD numeric token exceeds integer bound");
    }
  }

  private static boolean matchesBuild(String token, String selected) throws SchemaException {
    String[] range = token.split("-", -1);
    if (range.length < 1 || range.length > 2) throw error(MALFORMED, "Invalid DBD build selector");
    long[] low = build(range[0]), target = build(selected);
    if (range.length == 1) return Arrays.equals(low, target);
    long[] high = build(range[1]);
    if (compare(low, high) > 0) throw error(MALFORMED, "Reversed DBD build range");
    return compare(low, target) <= 0 && compare(target, high) <= 0;
  }

  private static long[] build(String value) throws SchemaException {
    if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))
      throw error(MALFORMED, "Invalid DBD build selector");
    String[] parts = value.split("\\.");
    long[] result = new long[4];
    for (int i = 0; i < 4; i++) {
      try {
        result[i] = Long.parseLong(parts[i]);
      } catch (NumberFormatException invalid) {
        throw error(LIMIT, "DBD build component exceeds bound");
      }
    }
    return result;
  }

  private static int compare(long[] first, long[] second) {
    for (int i = 0; i < 4; i++) {
      int c = Long.compare(first[i], second[i]);
      if (c != 0) return c;
    }
    return 0;
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void check(BooleanSupplier cancelled) throws SchemaException {
    if (cancelled.getAsBoolean()) throw error(CANCELLED, "Selected DBD parsing cancelled");
  }

  private static SchemaException error(SchemaException.Reason reason, String message) {
    return new SchemaException(reason, message);
  }
}
