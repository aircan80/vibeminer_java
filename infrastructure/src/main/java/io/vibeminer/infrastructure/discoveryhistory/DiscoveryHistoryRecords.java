package io.vibeminer.infrastructure.discoveryhistory;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildlibrary.BuildReferenceGroupKey;
import io.vibeminer.application.discoveryhistory.DiscoveryEvent;
import io.vibeminer.application.discoveryhistory.DiscoveryEventSummary;
import io.vibeminer.application.discoveryhistory.DiscoveryEventSummary.DocumentReceipt;
import io.vibeminer.domain.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Bounded observation metadata. Captured bodies live exclusively in shared source blobs. */
public final class DiscoveryHistoryRecords {
  public static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;
  private static final int MAX_TEXT_BYTES = 65536;
  private static final String MAGIC = "VMDH1";

  private DiscoveryHistoryRecords() {}

  /** Admission refusal for otherwise well-formed metadata exceeding the codec budget. */
  static final class LimitException extends IllegalArgumentException {
    LimitException() {
      super("Discovery history metadata exceeds codec budget");
    }
  }

  public static byte[] event(DiscoveryEvent event) {
    try {
      Objects.requireNonNull(event);
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      text(out, MAGIC);
      text(out, event.eventId().toString());
      text(out, event.productId());
      text(out, event.region());
      text(out, event.completedAt().toString());
      text(out, event.parserRevision());
      source(out, event.versions());
      source(out, event.cdns());
      out.writeInt(event.selectedCdn().rowOrdinal());
      row(out, event.cdns().columns(), event.selectedCdn().values());
      out.writeInt(event.builds().size());
      for (var row : event.builds()) {
        out.writeInt(row.versionsRowOrdinal());
        row(out, event.versions().columns(), row.versionsValues());
        var b = row.build();
        text(out, b.snapshotId());
        text(out, b.productId());
        text(out, b.editionLabel());
        text(out, b.channel());
        text(out, b.region());
        text(out, b.versionLabel());
        text(out, b.buildConfig());
        text(out, b.cdnConfig());
        text(out, b.observedAt().toString());
        out.writeBoolean(b.fixture());
        text(out, b.evidence().upstreamBuildId());
        text(out, b.evidence().productConfig());
        if (bytes.size() > MAX_RECORD_BYTES) throw new LimitException();
      }
      var result = bytes.toByteArray();
      // Validate exactly the same metadata grammar used by both summary and selected detail.
      metadata(result);
      return result;
    } catch (LimitException refusal) {
      throw refusal;
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  public static DiscoveryEvent event(byte[] bytes, SourceDocument versions, SourceDocument cdns) {
    try {
      var value = metadata(bytes);
      if (!value.versions.receipt.equals(DocumentReceipt.from(versions))
          || !value.cdns.receipt.equals(DocumentReceipt.from(cdns))) throw invalid(null);
      var documents = List.of(versions, cdns);
      var builds =
          value.builds.stream()
              .map(
                  row ->
                      new DiscoveryEvent.ObservedBuild(
                          withDocuments(row.build, documents), row.ordinal, row.values))
              .toList();
      return new DiscoveryEvent(
          value.id,
          value.product,
          value.region,
          value.at,
          value.revision,
          new DiscoveryEvent.ParsedSource(
              versions, value.versions.columns, value.versions.sequence),
          new DiscoveryEvent.ParsedSource(cdns, value.cdns.columns, value.cdns.sequence),
          value.cdn,
          builds);
    } catch (RuntimeException failure) {
      throw invalid(failure);
    }
  }

  /** Validates the entire metadata grammar without constructing or accessing body arrays. */
  public static DiscoveryEventSummary summary(byte[] bytes, long sequence) {
    try {
      var value = metadata(bytes);
      return summary(value, sequence);
    } catch (RuntimeException failure) {
      throw invalid(failure);
    }
  }

  /** Body-free reader projection; selection and registration still require exact source replay. */
  public static List<BuildLibraryReference> libraryReferences(byte[] record, long sequence) {
    try {
      var value = metadata(record);
      return value.builds.stream()
          .map(
              row ->
                  new BuildLibraryReference(
                      BuildReferenceGroupKey.from(
                          new DiscoveryEvent.ObservedBuild(row.build, row.ordinal, row.values)),
                      value.id,
                      sequence,
                      row.build.snapshotId(),
                      row.ordinal,
                      value.region,
                      value.at,
                      value.versions.receipt,
                      value.cdns.receipt,
                      value.cdn))
          .toList();
    } catch (RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private static DiscoveryEventSummary summary(Metadata value, long sequence) {
    return new DiscoveryEventSummary(
        value.id,
        sequence,
        value.product,
        value.region,
        value.at,
        value.revision,
        value.versions.receipt,
        value.cdns.receipt,
        value.builds.stream()
            .map(
                row ->
                    new DiscoveryEventSummary.BuildReference(
                        row.build.snapshotId(),
                        row.ordinal,
                        row.build.channel(),
                        row.build.versionLabel(),
                        row.build.buildConfig(),
                        row.build.cdnConfig(),
                        row.build.evidence().upstreamBuildId(),
                        row.build.evidence().productConfig()))
            .toList());
  }

  private record Parsed(DocumentReceipt receipt, List<String> columns, Optional<String> sequence) {}

  private record BuildRow(BuildSnapshot build, int ordinal, Map<String, String> values) {}

  private record Metadata(
      UUID id,
      String product,
      String region,
      Instant at,
      String revision,
      Parsed versions,
      Parsed cdns,
      DiscoveryEvent.CdnRow cdn,
      List<BuildRow> builds) {}

  private static Metadata metadata(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_RECORD_BYTES) throw invalid(null);
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (!MAGIC.equals(text(in))) throw invalid(null);
      var idText = text(in);
      var id = UUID.fromString(idText);
      if (!id.toString().equals(idText)) throw invalid(null);
      var product = text(in);
      var region = text(in);
      var at = Instant.parse(text(in));
      var revision = text(in);
      var versions = source(in);
      var cdns = source(in);
      int cdnOrdinal = ordinal(in);
      var cdn = new DiscoveryEvent.CdnRow(cdnOrdinal, row(in, cdns.columns));
      if (!region.equals(cdn.values().get("Name"))
          || blank(cdn.values().get("Path"))
          || blank(cdn.values().get("Hosts"))) throw invalid(null);
      int count = count(in, 64);
      var builds = new ArrayList<BuildRow>(count);
      int previous = -1;
      for (int i = 0; i < count; i++) {
        int ordinal = ordinal(in);
        if (ordinal <= previous) throw invalid(null);
        previous = ordinal;
        var values = row(in, versions.columns);
        var snapshotId = text(in);
        var buildProduct = text(in);
        var label = text(in);
        var channel = text(in);
        var buildRegion = text(in);
        var version = text(in);
        var buildConfig = text(in);
        var cdnConfig = text(in);
        var observedAt = Instant.parse(text(in));
        boolean fixture = flag(in);
        var buildId = text(in);
        var productConfig = text(in);
        var build =
            new BuildSnapshot(
                buildProduct,
                label,
                channel,
                buildRegion,
                version,
                buildConfig,
                cdnConfig,
                observedAt,
                fixture,
                new BuildEvidence(buildId, productConfig, List.of()));
        if (!snapshotId.equals(build.snapshotId())
            || fixture
            || !product.equals(buildProduct)
            || !region.equals(buildRegion)
            || !observedAt.equals(at)
            || !buildConfig.equals(build.buildConfig())
            || !cdnConfig.equals(build.cdnConfig())
            || !productConfig.equals(build.evidence().productConfig())
            || !region.equals(values.get("Region"))
            || !version.equals(values.get("VersionsName"))
            || !buildConfig.equalsIgnoreCase(values.get("BuildConfig"))
            || !cdnConfig.equalsIgnoreCase(values.get("CDNConfig"))
            || !buildId.equals(values.getOrDefault("BuildId", ""))
            || !productConfig.equalsIgnoreCase(values.getOrDefault("ProductConfig", "")))
          throw invalid(null);
        builds.add(new BuildRow(build, ordinal, values));
      }
      if (in.read() != -1) throw invalid(null);
      var value =
          new Metadata(id, product, region, at, revision, versions, cdns, cdn, List.copyOf(builds));
      summary(value, 1); // Exact source-role, identity, ordering and public scalar constraints.
      return value;
    } catch (IOException | RuntimeException failure) {
      throw invalid(failure);
    }
  }

  private static BuildSnapshot withDocuments(BuildSnapshot b, List<SourceDocument> documents) {
    return new BuildSnapshot(
        b.productId(),
        b.editionLabel(),
        b.channel(),
        b.region(),
        b.versionLabel(),
        b.buildConfig(),
        b.cdnConfig(),
        b.observedAt(),
        b.fixture(),
        new BuildEvidence(b.evidence().upstreamBuildId(), b.evidence().productConfig(), documents));
  }

  private static void source(DataOutputStream out, DiscoveryEvent.ParsedSource source)
      throws IOException {
    var receipt = DocumentReceipt.from(source.source());
    text(out, receipt.sourceUrl());
    text(out, receipt.capturedAt().toString());
    text(out, receipt.sha256());
    out.writeLong(receipt.length());
    out.writeInt(source.columns().size());
    for (var column : source.columns()) text(out, column);
    out.writeBoolean(source.sequence().isPresent());
    if (source.sequence().isPresent()) text(out, source.sequence().get());
  }

  private static Parsed source(DataInputStream in) throws IOException {
    var receipt = new DocumentReceipt(text(in), Instant.parse(text(in)), text(in), in.readLong());
    int count = count(in, 64);
    var columns = new ArrayList<String>(count);
    var unique = new HashSet<String>();
    for (int i = 0; i < count; i++) {
      var column = text(in);
      if (column.length() > 16384
          || !column.matches("[A-Za-z][A-Za-z0-9_]*")
          || !unique.add(column)) throw invalid(null);
      columns.add(column);
    }
    var sequence = flag(in) ? Optional.of(text(in)) : Optional.<String>empty();
    if (sequence.isPresent()
        && (sequence.get().length() > 16384 || !sequence.get().matches("[0-9]+")))
      throw invalid(null);
    return new Parsed(receipt, List.copyOf(columns), sequence);
  }

  private static void row(DataOutputStream out, List<String> columns, Map<String, String> values)
      throws IOException {
    if (!values.keySet().equals(new HashSet<>(columns))) throw invalid(null);
    for (var column : columns) text(out, values.get(column));
  }

  private static Map<String, String> row(DataInputStream in, List<String> columns)
      throws IOException {
    var result = new LinkedHashMap<String, String>();
    long length = columns.size() - 1;
    for (var column : columns) {
      var value = text(in);
      if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('|') >= 0)
        throw invalid(null);
      length += value.length();
      if (length > 16384) throw invalid(null);
      result.put(column, value);
    }
    return Map.copyOf(result);
  }

  private static int count(DataInputStream in, int maximum) throws IOException {
    int value = in.readInt();
    if (value < 1 || value > maximum) throw invalid(null);
    return value;
  }

  private static int ordinal(DataInputStream in) throws IOException {
    int value = in.readInt();
    if (value < 0 || value >= 10000) throw invalid(null);
    return value;
  }

  private static boolean flag(DataInputStream in) throws IOException {
    int value = in.readUnsignedByte();
    if (value > 1) throw invalid(null);
    return value == 1;
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    var encoded =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
    if (encoded.remaining() > MAX_TEXT_BYTES) throw invalid(null);
    out.writeInt(encoded.remaining());
    while (encoded.hasRemaining()) out.writeByte(encoded.get());
  }

  private static String text(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > MAX_TEXT_BYTES || size > in.available()) throw invalid(null);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(in.readNBytes(size)))
        .toString();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static IllegalArgumentException invalid(Throwable cause) {
    return new IllegalArgumentException("Invalid bounded discovery history metadata", cause);
  }
}
