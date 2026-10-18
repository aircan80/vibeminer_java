package io.vibeminer.infrastructure.schema;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.format.wow.schema.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class BundledItemSchemasTest {
  private final BundledItemSchemas schemas = new BundledItemSchemas();

  @Test
  void selectsPinnedItemWithFifteenPhysicalFieldsAndConfidenceMetadata() throws Exception {
    var result =
        schemas.select(
            "wow",
            BundledItemSchemas.BUILD,
            BundledItemSchemas.Table.ITEM,
            new SchemaSelection.HeaderFacts(0x50238ec2L, 0x996192aaL, 15, 4),
            () -> false);
    assertEquals(SelectedSchema.Status.MATCH, result.status());
    assertEquals(16, result.fields().size());
    assertEquals(15, result.fields().stream().filter(f -> !f.noninline()).count());
    assertTrue(
        result.fields().stream()
            .filter(f -> f.name().equals("ContentTuningID"))
            .findFirst()
            .orElseThrow()
            .unverified());
    assertEquals(BundledItemSchemas.REVISION, result.provenance().revision());
    assertFalse(result.provenance().modified());
    assertEquals("CC BY-SA-4.0", result.provenance().license());
  }

  @Test
  void selectsPinnedSparseWithSixtySevenFieldsArraysAndFifthLocalizedName() throws Exception {
    var result =
        schemas.select(
            "wow",
            BundledItemSchemas.BUILD,
            BundledItemSchemas.Table.ITEM_SPARSE,
            new SchemaSelection.HeaderFacts(0x919be54eL, 0x1c17d17fL, 67, 5),
            () -> false);
    assertEquals(SelectedSchema.Status.MATCH, result.status());
    assertEquals(68, result.fields().size());
    assertEquals("Display_lang", result.fields().get(5).name());
    assertEquals(SelectedSchema.Type.LOCSTRING, result.fields().get(5).type());
    assertEquals(
        10,
        result.fields().stream()
            .filter(f -> f.name().equals("StatPercentEditor"))
            .findFirst()
            .orElseThrow()
            .arrayLength());
    assertTrue(result.fields().getFirst().noninline());
  }

  @Test
  void neverBindsOtherProductBuildOrWrongHeader() throws Exception {
    var table = BundledItemSchemas.Table.ITEM;
    var header = new SchemaSelection.HeaderFacts(1, 2, 15, 4);
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        schemas
            .select("wow_classic", BundledItemSchemas.BUILD, table, header, () -> false)
            .status());
    assertEquals(
        SelectedSchema.Status.MISSING,
        schemas.select("wow", "12.1.5.70449", table, header, () -> false).status());
    assertEquals(
        SelectedSchema.Status.INCOMPATIBLE,
        schemas.select("wow", BundledItemSchemas.BUILD, table, header, () -> false).status());
  }

  @Test
  void checksResourceDigestAndDoesNotAcquireAfterCancellation() {
    var table = BundledItemSchemas.Table.ITEM;
    var header = new SchemaSelection.HeaderFacts(0x50238ec2L, 0x996192aaL, 15, 4);
    var bad = new BundledItemSchemas(path -> new ByteArrayInputStream(new byte[34931]));
    assertThrows(
        IOException.class,
        () -> bad.select("wow", BundledItemSchemas.BUILD, table, header, () -> false));
    var never =
        new BundledItemSchemas(
            path -> {
              throw new AssertionError("No resource lookup after cancellation");
            });
    assertThrows(
        SchemaException.class,
        () -> never.select("wow", BundledItemSchemas.BUILD, table, header, () -> true));
  }

  @Test
  void distributesFullUpstreamLicenseAndSeparateDataAttribution() throws Exception {
    try (var stream =
        BundledItemSchemas.class.getResourceAsStream(BundledItemSchemas.ROOT + "LICENSE.md")) {
      assertNotNull(stream);
      byte[] bytes = stream.readAllBytes();
      assertEquals(17689, bytes.length);
      assertEquals(
          "bab6b1d3130b236c5af360ee7bfa00d8757329d054323441ed6a5eb367cd1f32",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    try (var stream =
        BundledItemSchemas.class.getResourceAsStream(BundledItemSchemas.ROOT + "NOTICE.txt")) {
      assertNotNull(stream);
      String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(text.contains("CC BY-SA 4.0"));
      assertTrue(text.contains("BSD-3-Clause"));
      assertTrue(text.contains("UNMODIFIED"));
      assertTrue(text.contains(BundledItemSchemas.REVISION));
    }
  }
}
