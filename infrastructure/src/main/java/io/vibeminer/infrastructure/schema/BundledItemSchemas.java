package io.vibeminer.infrastructure.schema;

import io.vibeminer.format.wow.schema.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Digest-pinned, explicit-build classpath provider. No network refresh or filesystem lookup. */
public final class BundledItemSchemas {
  public static final String REVISION = "b9a63096cdd99b26967f17f9515377ae62cadfd7";
  public static final String BUILD = "12.1.5.70448";
  static final String ROOT = "/io/vibeminer/schema/wowdbdefs/" + REVISION + "/";

  public enum Table {
    ITEM(
        "Item",
        0x50238ec2L,
        0x996192aaL,
        15,
        false,
        34931,
        "076ea4c7e80c891b399cc77f285c807071080c0fed95701e447f7c98024ae120"),
    ITEM_SPARSE(
        "ItemSparse",
        0x919be54eL,
        0x1c17d17fL,
        67,
        true,
        75858,
        "8664c3b5862dfe4c3a4322941b48b032c57b90412c070deb2028f3e448cf674a");
    private final String name, digest;
    private final long tableHash, layoutHash;
    private final int fields, bytes;
    private final boolean sparse;

    Table(
        String name,
        long tableHash,
        long layoutHash,
        int fields,
        boolean sparse,
        int bytes,
        String digest) {
      this.name = name;
      this.tableHash = tableHash;
      this.layoutHash = layoutHash;
      this.fields = fields;
      this.sparse = sparse;
      this.bytes = bytes;
      this.digest = digest;
    }

    public SchemaSelection selection(String product, String build) {
      return new SchemaSelection(product, build, name, tableHash, layoutHash, fields, sparse, true);
    }

    public String sha256() {
      return digest;
    }
  }

  @FunctionalInterface
  interface ResourceLoader {
    InputStream open(String path) throws IOException;
  }

  private final ResourceLoader resources;

  public BundledItemSchemas() {
    this(BundledItemSchemas.class::getResourceAsStream);
  }

  BundledItemSchemas(ResourceLoader resources) {
    this.resources = Objects.requireNonNull(resources);
  }

  public SelectedSchema select(
      String product,
      String build,
      Table table,
      SchemaSelection.HeaderFacts observed,
      BooleanSupplier cancelled)
      throws IOException, SchemaException {
    Objects.requireNonNull(table);
    Objects.requireNonNull(observed);
    Objects.requireNonNull(cancelled);
    check(cancelled);
    var selection = table.selection(product, build);
    var provenance =
        new SelectedSchema.Provenance(
            "WoWDBDefs",
            REVISION,
            "https://github.com/wowdev/WoWDBDefs/blob/"
                + REVISION
                + "/definitions/"
                + table.name
                + ".dbd",
            table.digest,
            "CC BY-SA-4.0",
            "WoWDBDefs contributors; unmodified definitions; bundled NOTICE.txt and LICENSE.md",
            false);
    if (!product.equals("wow"))
      return new SelectedSchema(
          SelectedSchema.Status.INCOMPATIBLE,
          selection,
          provenance,
          List.of(),
          List.of("Bundled provider supports explicit product wow only"));
    if (!build.equals(BUILD))
      return new SelectedSchema(
          SelectedSchema.Status.MISSING,
          selection,
          provenance,
          List.of(),
          List.of("Build is outside bundled reviewed cohort; no fallback"));
    byte[] bytes;
    try (InputStream stream = resources.open(ROOT + table.name + ".dbd")) {
      if (stream == null) throw new IOException("Pinned schema resource unavailable");
      bytes = stream.readNBytes(table.bytes + 1);
    }
    check(cancelled);
    if (bytes.length != table.bytes || !digest(bytes).equals(table.digest))
      throw new IOException("Pinned schema resource length/digest mismatch");
    return new SelectedDbdParser()
        .parse(
            bytes,
            selection,
            observed,
            provenance,
            new SelectedDbdParser.Limits(128 * 1024, 5000, 8192, 512, 512, 1024),
            cancelled);
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void check(BooleanSupplier cancelled) throws SchemaException {
    if (cancelled.getAsBoolean())
      throw new SchemaException(
          SchemaException.Reason.CANCELLED, "Bundled schema selection cancelled");
  }
}
