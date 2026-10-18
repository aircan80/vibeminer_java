package io.vibeminer.format.wow.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectedDbdParserTest {
  private static final String FIXTURE =
      """
      COLUMNS
      int ID
      int<Other::ID> Value? // guessed
      locstring Name

      LAYOUT 00000002
      BUILD 1.2.3.4
      COMMENT table is sparse
      $noninline,id$ID<32>
      Value<u16>[3]
      Name
      """;
  private final SelectedDbdParser parser = new SelectedDbdParser();
  private final SelectedDbdParser.Limits limits =
      new SelectedDbdParser.Limits(10000, 1000, 1000, 100, 100, 1000);

  private SchemaSelection selection(String product, String build, int count, boolean sparse) {
    return new SchemaSelection(product, build, "Table", 1, 2, count, sparse, true);
  }

  private SelectedSchema.Provenance source(byte[] bytes) throws Exception {
    return new SelectedSchema.Provenance(
        "Synthetic",
        "revision",
        "https://example.invalid/Table.dbd",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        "CC BY-SA-4.0",
        "Authored synthetic fixture",
        false);
  }

  private SelectedSchema parse(String text, SchemaSelection selected) throws Exception {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return parser.parse(
        bytes,
        selected,
        new SchemaSelection.HeaderFacts(1, 2, 2, 5),
        source(bytes),
        limits,
        () -> false);
  }

  @Test
  void exactSelectionPreservesTypesAnnotationsRelationsUnknownNamesAndArrays() throws Exception {
    var result = parse(FIXTURE, selection("wow", "1.2.3.4", 2, true));
    assertEquals(SelectedSchema.Status.MATCH, result.status());
    assertEquals(3, result.fields().size());
    var field = result.fields().get(1);
    assertEquals("Value", field.name());
    assertEquals("Value?", field.rawColumnName());
    assertTrue(field.unverified());
    assertEquals(new SelectedSchema.Relation("Other", "ID"), field.relation().orElseThrow());
    assertEquals(16, field.integerBits());
    assertEquals(SelectedSchema.Sign.UNSIGNED, field.sign());
    assertEquals(3, field.arrayLength());
    assertTrue(result.fields().getFirst().noninline());
    assertTrue(result.fields().getFirst().id());
    assertEquals(SelectedSchema.Type.LOCSTRING, result.fields().get(2).type());
    assertThrows(UnsupportedOperationException.class, () -> result.fields().clear());
    assertEquals("revision", result.provenance().revision());
  }

  @Test
  void missingBuildAndLayoutNeverFallBack() throws Exception {
    assertEquals(
        SelectedSchema.Status.MISSING,
        parse(FIXTURE, selection("wow", "1.2.3.5", 2, true)).status());
    assertEquals(
        SelectedSchema.Status.MISSING,
        parse(FIXTURE.replace("00000002", "00000003"), selection("wow", "1.2.3.4", 2, true))
            .status());
  }

  @Test
  void duplicateCompatibleSelectorsAreAmbiguous() throws Exception {
    String block = FIXTURE.substring(FIXTURE.indexOf("LAYOUT"));
    assertEquals(
        SelectedSchema.Status.AMBIGUOUS,
        parse(FIXTURE + "\n" + block, selection("wow", "1.2.3.4", 2, true)).status());
  }

  @Test
  void explicitProductHeaderCountAndSparseAgreementRequired() throws Exception {
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        parse(FIXTURE, selection("wow_classic", "1.2.3.4", 2, true)).status());
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        parse(FIXTURE, selection("wow", "1.2.3.4", 3, true)).status());
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        parse(FIXTURE, selection("wow", "1.2.3.4", 2, false)).status());
    byte[] bytes = FIXTURE.getBytes(StandardCharsets.UTF_8);
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        parser
            .parse(
                bytes,
                selection("wow", "1.2.3.4", 2, true),
                new SchemaSelection.HeaderFacts(9, 2, 2, 5),
                source(bytes),
                limits,
                () -> false)
            .status());
  }

  @Test
  void rangesSelectExplicitBuildWithoutChangingSavedScope() throws Exception {
    assertEquals(
        SelectedSchema.Status.MATCH,
        parse(
                FIXTURE.replace("BUILD 1.2.3.4", "BUILD 1.2.3.1-1.2.3.6"),
                selection("wow", "1.2.3.4", 2, true))
            .status());
    assertEquals(
        SelectedSchema.Status.MISSING,
        parse(
                FIXTURE.replace("BUILD 1.2.3.4", "BUILD 1.2.3.1-1.2.3.3"),
                selection("wow", "1.2.3.4", 2, true))
            .status());
  }

  @Test
  void malformedSelectedColumnsAndUnknownAnnotationsAreTyped() throws Exception {
    for (String malformed :
        new String[] {
          "Missing<8>",
          "Value<u7>",
          "Value<0>",
          "Value<u0>",
          "Value<u16>[0]",
          "$mystery$Value<u16>",
          "Name<32>"
        }) {
      String text = FIXTURE.replace("Value<u16>[3]", malformed);
      assertThrows(SchemaException.class, () -> parse(text, selection("wow", "1.2.3.4", 2, true)));
    }
  }

  @Test
  void humanCommentsNeverDetermineSparseStructure() throws Exception {
    for (String comment :
        new String[] {"", "COMMENT arbitrary human note", "COMMENT table is NOT sparse"}) {
      var result =
          parse(
              FIXTURE.replace("COMMENT table is sparse\n", comment.isEmpty() ? "" : comment + "\n"),
              selection("wow", "1.2.3.4", 2, true));
      assertEquals(SelectedSchema.Status.MATCH, result.status());
      assertEquals(3, result.fields().size());
    }
    byte[] bytes = FIXTURE.getBytes(StandardCharsets.UTF_8);
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        parser
            .parse(
                bytes,
                selection("wow", "1.2.3.4", 2, true),
                new SchemaSelection.HeaderFacts(1, 2, 2, 4),
                source(bytes),
                limits,
                () -> false)
            .status());
  }

  @Test
  void commentOnlyLinesDoNotSplitLayoutAndSignedWidthsRemainExplicit() throws Exception {
    var result =
        parse(
            FIXTURE.replace("Value<u16>[3]", "// selected comment\nValue<16>[3]"),
            selection("wow", "1.2.3.4", 2, true));
    assertEquals(SelectedSchema.Status.MATCH, result.status());
    assertEquals(SelectedSchema.Sign.SIGNED, result.fields().get(1).sign());
  }

  @Test
  void rejectsMalformedSelectorsAndInvalidIdCandidateWithoutGuessing() throws Exception {
    for (String selector :
        new String[] {
          "BUILD 1.2.3.6-1.2.3.1",
          "BUILD 1.2.3",
          "BUILD 1.2.3.4, ",
          "BUILD 1.2.3.9999999999999999999999999"
        })
      assertThrows(
          SchemaException.class,
          () ->
              parse(
                  FIXTURE.replace("BUILD 1.2.3.4", selector),
                  selection("wow", "1.2.3.4", 2, true)));
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        parse(FIXTURE.replace("ID<32>", "ID<32>[2]"), selection("wow", "1.2.3.4", 2, true))
            .status());
  }

  @Test
  void digestStrictUtf8LimitsAndCancellationAreChecked() throws Exception {
    byte[] bytes = FIXTURE.getBytes(StandardCharsets.UTF_8);
    assertThrows(
        SchemaException.class,
        () ->
            parser.parse(
                new byte[] {(byte) 0xff},
                selection("wow", "1.2.3.4", 2, true),
                new SchemaSelection.HeaderFacts(1, 2, 2, 5),
                source(new byte[] {(byte) 0xff}),
                limits,
                () -> false));
    assertThrows(
        SchemaException.class,
        () ->
            parser.parse(
                bytes,
                selection("wow", "1.2.3.4", 2, true),
                new SchemaSelection.HeaderFacts(1, 2, 2, 5),
                source(new byte[0]),
                limits,
                () -> false));
    var small = new SelectedDbdParser.Limits(1, 1000, 1000, 100, 100, 1000);
    assertEquals(
        SchemaException.Reason.LIMIT,
        assertThrows(
                SchemaException.class,
                () ->
                    parser.parse(
                        bytes,
                        selection("wow", "1.2.3.4", 2, true),
                        new SchemaSelection.HeaderFacts(1, 2, 2, 5),
                        source(bytes),
                        small,
                        () -> false))
            .reason());
    AtomicInteger calls = new AtomicInteger();
    assertEquals(
        SchemaException.Reason.CANCELLED,
        assertThrows(
                SchemaException.class,
                () ->
                    parser.parse(
                        bytes,
                        selection("wow", "1.2.3.4", 2, true),
                        new SchemaSelection.HeaderFacts(1, 2, 2, 5),
                        source(bytes),
                        limits,
                        () -> calls.incrementAndGet() > 3))
            .reason());
  }
}
