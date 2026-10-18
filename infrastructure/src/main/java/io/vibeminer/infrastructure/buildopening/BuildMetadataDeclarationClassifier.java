package io.vibeminer.infrastructure.buildopening;

import static io.vibeminer.application.buildopening.BuildOpeningException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.buildopening.BuildOpeningException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.MetadataStructureAttestation.Kind;
import io.vibeminer.protocol.tact.config.*;
import java.security.*;
import java.util.*;

/** Concrete ROOT-mode WoW profile. No network, source acquisition, or durable admission. */
public final class BuildMetadataDeclarationClassifier {
  public static final String REVISION = "wow-root-metadata-declarations-v1";

  public record Limits(
      int maximumConfigBytes,
      int maximumLines,
      int maximumLineCharacters,
      int maximumTokensPerValue,
      int maximumProperties,
      long maximumEncodedBytes,
      long maximumDecodedBytes) {
    public Limits {
      if (maximumConfigBytes < 1
          || maximumLines < 1
          || maximumLineCharacters < 1
          || maximumTokensPerValue < 1
          || maximumProperties < 1
          || maximumEncodedBytes < 1
          || maximumDecodedBytes < 1)
        throw new IllegalArgumentException("Positive declaration limits required");
    }

    public static Limits defaults() {
      return new Limits(1 << 20, 16384, 16384, 64, 4096, 1L << 30, 2L << 30);
    }
  }

  public BuildMetadataDeclarationPlan classify(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      byte[] exactRaw,
      Limits limits,
      CancellationToken cancellation) {
    Objects.requireNonNull(build);
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(exactRaw);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(cancellation).throwIfCancellationRequested();
    if (!build.productId().equals("wow"))
      throw fail(UNSUPPORTED_METADATA, "Only reviewed raw wow metadata profile is admitted");
    var expected =
        new ResolvedSourceIdentity(
            "tact.config.raw-md5", build.buildConfig(), "tact-config-raw-md5-v1");
    if (!configuration.source().equals(expected)
        || configuration.object().length() != exactRaw.length)
      throw fail(CORRUPT_METADATA, "Configuration differs from exact build or retained length");
    if (exactRaw.length > limits.maximumConfigBytes())
      throw fail(LIMIT_EXCEEDED, "Configuration exceeds byte budget");
    // One bounded private copy keeps SHA, MD5 and parsing on the same immutable input.
    exactRaw = exactRaw.clone();
    try {
      var sha = MessageDigest.getInstance("SHA-256");
      for (int offset = 0; offset < exactRaw.length; offset += 8192) {
        cancellation.throwIfCancellationRequested();
        sha.update(exactRaw, offset, Math.min(8192, exactRaw.length - offset));
      }
      if (!HexFormat.of().formatHex(sha.digest()).equals(configuration.object().digest().value()))
        throw fail(CORRUPT_METADATA, "Configuration physical SHA256 mismatch");
      var parsed =
          new TactConfigParser()
              .parse(
                  exactRaw,
                  build.buildConfig(),
                  new TactConfigParser.Limits(
                      limits.maximumConfigBytes(),
                      limits.maximumLines(),
                      limits.maximumLineCharacters(),
                      limits.maximumTokensPerValue(),
                      limits.maximumDecodedBytes(),
                      limits.maximumEncodedBytes()),
                  cancellation::isCancellationRequested);
      var values = parsed.values();
      if (values.size() > limits.maximumProperties())
        throw fail(LIMIT_EXCEEDED, "Configuration property budget exceeded");
      var sources = new EnumMap<Kind, BuildMetadataDeclarationPlan.Source>(Kind.class);
      var root = values.get("root");
      if (root == null || root.size() != 1 || !hash(root.getFirst()))
        throw fail(
            UNSUPPORTED_METADATA,
            "Required single nonzero ROOT CKey is unsupported; no VFS fallback");
      if (values.containsKey("root-size"))
        throw fail(UNSUPPORTED_METADATA, "ROOT size companion needs explicit profile admission");
      sources.put(
          Kind.ROOT,
          new BuildMetadataDeclarationPlan.Source(
              Kind.ROOT,
              key(root.getFirst()),
              Optional.empty(),
              OptionalLong.empty(),
              OptionalLong.empty()));
      sources.put(Kind.ENCODING, source("encoding", Kind.ENCODING, values, limits, true));
      sources.put(Kind.INSTALL, source("install", Kind.INSTALL, values, limits, false));
      for (var entry : Map.of("download", Kind.DOWNLOAD, "size", Kind.DOWNLOAD_SIZE).entrySet()) {
        cancellation.throwIfCancellationRequested();
        if (values.containsKey(entry.getKey()))
          sources.put(
              entry.getValue(), source(entry.getKey(), entry.getValue(), values, limits, false));
        else if (values.containsKey(entry.getKey() + "-size"))
          throw fail(CORRUPT_METADATA, "Orphan declaration: " + entry.getKey() + "-size");
      }
      var classifications =
          new LinkedHashMap<String, BuildMetadataDeclarationPlan.Classification>();
      for (var entry : values.entrySet()) {
        cancellation.throwIfCancellationRequested();
        var field = entry.getKey();
        BuildMetadataDeclarationPlan.Classification classification;
        if (Set.of(
                "root",
                "encoding",
                "encoding-size",
                "install",
                "install-size",
                "download",
                "download-size",
                "size",
                "size-size")
            .contains(field))
          classification = BuildMetadataDeclarationPlan.Classification.REQUIRED_METADATA;
        else if (field.matches("vfs-(root|[1-9][0-9]*)(-size)?"))
          classification = BuildMetadataDeclarationPlan.Classification.ALTERNATIVE_RESOLVER;
        else if (Set.of("patch", "patch-size", "patch-index", "patch-index-size").contains(field))
          classification = BuildMetadataDeclarationPlan.Classification.DELTA_UPDATE;
        else {
          if (potentialReference(field, entry.getValue(), values))
            throw fail(
                UNSUPPORTED_METADATA,
                "Unclassified potential reference under "
                    + REVISION
                    + ": "
                    + field
                    + " = "
                    + entry.getValue());
          classification = BuildMetadataDeclarationPlan.Classification.INFORMATIONAL;
        }
        classifications.put(field, classification);
      }
      return BuildMetadataDeclarationPlan.issued(
          build, configuration, sources, values, classifications);
    } catch (TactConfigException failure) {
      if (failure.reason() == TactConfigException.Reason.CANCELLED) {
        cancellation.throwIfCancellationRequested();
        throw new java.util.concurrent.CancellationException("Configuration parsing cancelled");
      }
      throw new BuildOpeningException(
          failure.reason() == TactConfigException.Reason.LIMIT ? LIMIT_EXCEEDED : CORRUPT_METADATA,
          "Exact configuration parsing failed",
          failure);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static BuildMetadataDeclarationPlan.Source source(
      String field,
      Kind kind,
      Map<String, List<String>> values,
      Limits limits,
      boolean requireDirect) {
    var keys = values.get(field);
    var sizes = values.get(field + "-size");
    if (keys == null) throw fail(MISSING_METADATA, "Required declaration missing: " + field);
    if (keys.isEmpty()
        || keys.size() > 2
        || keys.stream().anyMatch(value -> !hash(value))
        || requireDirect && keys.size() != 2)
      throw fail(CORRUPT_METADATA, "Malformed metadata key declaration: " + field);
    if (keys.size() == 2 && sizes == null)
      throw fail(CORRUPT_METADATA, "Direct source requires both lengths: " + field);
    OptionalLong decoded = OptionalLong.empty(), encoded = OptionalLong.empty();
    if (sizes != null) {
      if (sizes.size() != 2)
        throw fail(CORRUPT_METADATA, "Both metadata lengths required: " + field);
      decoded = OptionalLong.of(length(sizes.get(0)));
      encoded = OptionalLong.of(length(sizes.get(1)));
      if (decoded.getAsLong() > limits.maximumDecodedBytes()
          || encoded.getAsLong() > limits.maximumEncodedBytes())
        throw fail(LIMIT_EXCEEDED, "Metadata source exceeds configured bounds: " + field);
    }
    return new BuildMetadataDeclarationPlan.Source(
        kind,
        key(keys.getFirst()),
        keys.size() == 2 ? Optional.of(key(keys.get(1))) : Optional.empty(),
        decoded,
        encoded);
  }

  private static boolean potentialReference(
      String field, List<String> tokens, Map<String, List<String>> values) {
    if (Set.of(
            "build-name",
            "build-uid",
            "build-product",
            "build-branch",
            "build-number",
            "build-attributes",
            "client-version",
            "feature-placeholder",
            "build-partial-priority",
            "product",
            "uid")
        .contains(field)) return false;
    return field.matches(".*(^|-)(manifest|root|index)(-|$).*")
        || values.containsKey(field + "-size")
        || field.endsWith("-size") && values.containsKey(field.substring(0, field.length() - 5))
        || tokens.stream().anyMatch(value -> value.matches("[0-9a-fA-F]{32}"));
  }

  private static boolean hash(String value) {
    return value.matches("[0-9a-fA-F]{32}") && !value.equals("0".repeat(32));
  }

  private static String key(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static long length(String value) {
    if (!value.matches("[1-9][0-9]{0,18}"))
      throw fail(CORRUPT_METADATA, "Positive canonical metadata length required");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException failure) {
      throw fail(CORRUPT_METADATA, "Metadata length exceeds signed long");
    }
  }

  private static BuildOpeningException fail(BuildOpeningException.Code code, String message) {
    return new BuildOpeningException(code, message);
  }
}
