package io.vibeminer.infrastructure.itemnames;

import static io.vibeminer.application.itemnames.ItemNamesException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.itemnames.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.format.wow.item.*;
import io.vibeminer.format.wow.schema.*;
import io.vibeminer.infrastructure.schema.BundledItemSchemas;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Offline Sparse source composition; no transport, database, publication or Item verification. */
public final class RetainedSparseNameProjectionSourceReader
    implements SparseNameProjectionSourceReader {
  public static final String WINDOWS_REVISION = "sparse-name-authenticated-windows-v1";
  public static final String DECODER_REVISION =
      SparsePhysicalNameProjector.PROJECTION_REVISION
          + ";wdc5-metadata-v1;sparse-index-v1;absolute-locator-v1;cstring-prefix-v1";
  public static final String INDEX_REVISION = "sparse-physical-display-index-v1";
  private static final SparseNameLimits VERIFY_LIMITS =
      new SparseNameLimits(
          16L << 20,
          131072,
          4096,
          4 << 20,
          4 << 20,
          131072,
          4 << 20,
          200000,
          1000,
          64L << 20,
          65535,
          65535,
          65535,
          64L << 20,
          256L << 20,
          500);
  private final ObjectStore store;
  private final SelectedFileCatalog catalog;
  private final SelectedFileGateway gateway;
  private final Clock clock;

  public RetainedSparseNameProjectionSourceReader(
      ObjectStore store, SelectedFileCatalog catalog, SelectedFileGateway gateway, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.catalog = Objects.requireNonNull(catalog);
    this.gateway = Objects.requireNonNull(gateway);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public OpenedNames open(ItemNamesRequest request, CancellationToken token) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    var variant = request.sparseSource().association();
    if (!request.item().snapshotId().equals(variant.build().snapshotId())
        || !request.item().locale().equals(request.locale()))
      throw failure(INVALID_REQUEST, "Explicit Item/Sparse source contexts differ");
    return load(request.sparseSource(), request.locale(), request.limits(), token);
  }

  private Opened load(
      SelectedFileDescriptor descriptor,
      String locale,
      SparseNameLimits limits,
      CancellationToken token) {
    ObjectReadLease lease = null;
    try {
      token.throwIfCancellationRequested();
      var v = descriptor.association();
      var b = v.build();
      if (!b.productId().equals("wow")
          || !b.region().equals("us")
          || !b.versionLabel().equals("12.1.5.70448")
          || !"enUS".equals(locale)
          || v.fileDataId() != 1572924
          || !v.contentKey().equals("bb0ecbdc2fd4988ddd3c1c5e95c34165")
          || (v.localeFlags() & 2) == 0)
        throw failure(UNSUPPORTED, "Sparse source is outside reviewed enUS cohort");
      var association =
          catalog
              .findOriginalAssociation(descriptor, token)
              .orElseThrow(
                  () ->
                      failure(MISSING_LOCAL, "Exact original Sparse association is not retained"));
      if (!association.role().equals(descriptor.role())
          || !association.snapshotId().equals(b.snapshotId()))
        throw failure(CORRUPT, "Original association changed exact descriptor identity");
      var representation = association.representation();
      long encodedBytes = representation.encoded().object().length();
      if (encodedBytes > limits.maxEncodedBytes())
        throw failure(
            LIMIT_EXCEEDED, "Sparse encoded object exceeds explicit materialization bound");
      // Separate root replay stage uses its existing independently bounded512MiB work policy.
      if (!gateway
          .inspectRetained(association, SelectedFileBudgets.defaults(), token)
          .equals(representation))
        throw failure(CORRUPT, "Offline replay changed original source representation");
      lease = store.openRead(representation.encoded().object().digest(), token);
      if (!lease.object().equals(representation.encoded().object()))
        throw failure(CORRUPT, "Sparse physical metadata changed");
      byte[] encoded;
      try (var verifier =
          new EncodedBlteInputStream(
              lease.stream(),
              descriptor.actualEncodingKey(),
              encodedBytes,
              new EncodedBlteInputStream.Limits(
                  limits.maxHeaderBytes(),
                  limits.maxChunks(),
                  limits.maxEncodedBytes(),
                  limits.maxEncodedBytes()),
              token::isCancellationRequested)) {
        encoded = verifier.readNBytes(Math.toIntExact(encodedBytes) + 1);
        var proof = verifier.completionProof();
        if (encoded.length != encodedBytes
            || !proof.physicalSha256().equals(representation.encoded().object().digest().value())
            || !proof.scope().name().equals(representation.scope().name()))
          throw failure(CORRUPT, "Complete Sparse encoded proof differs from retained source");
      }
      var physicalVerifiedAt = clock.instant();
      if (encoded.length < 12) throw failure(CORRUPT, "Truncated Sparse BLTE header");
      int headerBytes = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).getInt(4);
      if (headerBytes == 0)
        throw failure(UNSUPPORTED, "Zero-header Sparse window decoding is not admitted");
      if (headerBytes < 0 || headerBytes > limits.maxHeaderBytes() || headerBytes > encoded.length)
        throw failure(LIMIT_EXCEEDED, "Sparse header exceeds explicit bound");
      long decodedBytes =
          association
              .proof()
              .declaredDecodedBytes()
              .orElseThrow(
                  () ->
                      failure(
                          CORRUPT, "Authenticated Sparse decoded length declaration is missing"));
      var planner = new BlteChunkPlanner();
      var plan =
          planner.authenticate(
              Arrays.copyOf(encoded, headerBytes),
              HexFormat.of().parseHex(descriptor.actualEncodingKey()),
              encodedBytes,
              decodedBytes,
              new BlteChunkPlanner.Limits(
                  limits.maxHeaderBytes(),
                  limits.maxChunks(),
                  limits.maxEncodedBytes(),
                  128L << 20,
                  limits.maxChunkEncodedBytes(),
                  limits.maxChunkDecodedBytes()),
              token::isCancellationRequested);
      var chunks = new Chunks(encoded, planner, plan, limits.maxDecodedWorkBytes());
      var header =
          new Wdc5HeaderInspector()
              .inspect(
                  chunks.window(0, 204, token),
                  new Wdc5HeaderInspector.Limits(
                      limits.maxMetadataBytes(), 0xffffffffL, 128, 128, 1 << 20, 128L << 20),
                  token::isCancellationRequested);
      long prefixBytes =
          204
              + header.sectionCount() * 40
              + header.fieldCount() * 28
              + header.commonDataSize()
              + header.palletDataSize();
      if (prefixBytes > limits.maxMetadataBytes())
        throw failure(LIMIT_EXCEEDED, "Sparse metadata exceeds explicit bound");
      byte[] prefix = chunks.window(0, Math.toIntExact(prefixBytes), token);
      var metadata =
          new Wdc5MetadataInspector()
              .inspect(
                  prefix,
                  decodedBytes,
                  new Wdc5MetadataInspector.Limits(
                      limits.maxMetadataBytes(),
                      128L << 20,
                      0xffffffffL,
                      128,
                      128,
                      1 << 20,
                      limits.maxMetadataBytes()),
                  token::isCancellationRequested);
      if (metadata.sections().isEmpty())
        throw failure(UNSUPPORTED, "Sparse source has no admitted first section");
      var section = metadata.sections().getFirst();
      long suffixBytes = section.declaredEndOffset() - section.offsetRecordsEndOffset();
      if (suffixBytes < 0) throw failure(CORRUPT, "Sparse suffix has reversed extent");
      if (suffixBytes > limits.maxSuffixBytes())
        throw failure(LIMIT_EXCEEDED, "Sparse suffix exceeds explicit bound");
      byte[] suffix =
          chunks.window(section.offsetRecordsEndOffset(), Math.toIntExact(suffixBytes), token);
      var schema =
          new BundledItemSchemas()
              .select(
                  "wow",
                  b.versionLabel(),
                  BundledItemSchemas.Table.ITEM_SPARSE,
                  new SchemaSelection.HeaderFacts(
                      header.tableHash(), header.layoutHash(), header.fieldCount(), header.flags()),
                  token::isCancellationRequested);
      var projector = new SparsePhysicalNameProjector();
      var recipe =
          projector.prepare(
              metadata,
              schema,
              suffix,
              0,
              new SparsePhysicalNameProjector.Limits(
                  limits.maxMetadataBytes(),
                  limits.maxSuffixBytes(),
                  limits.maxPhysicalRows(),
                  limits.maxCopyDeclarations(),
                  limits.maxRecordAreaBytes(),
                  limits.maxRecordBytes(),
                  limits.maxStringBytes(),
                  limits.maxPrefixBytes(),
                  limits.maxProjectedUtf8Bytes()),
              token::isCancellationRequested);
      var copies = new ArrayList<CopyDeclaration>();
      var copyBytes = ByteBuffer.wrap(suffix).order(ByteOrder.LITTLE_ENDIAN);
      copyBytes.position(Math.toIntExact(section.idListSize()));
      for (int n = 0; n < section.copyTableCount(); n++) {
        token.throwIfCancellationRequested();
        copies.add(
            new CopyDeclaration(
                Integer.toUnsignedLong(copyBytes.getInt()),
                Integer.toUnsignedLong(copyBytes.getInt())));
      }
      var p = schema.provenance();
      var schemaIdentity =
          new SparseNameArtifactDefinition.Schema(
              p.provider(),
              p.revision(),
              "definitions/ItemSparse.dbd",
              p.sha256(),
              sha(
                  "wow\n12.1.5.70448\nItemSparse\n919BE54E\n1C17D17F\n67\nsparse-noninline-five-locstrings-v1"
                      .getBytes(StandardCharsets.UTF_8)),
              p.license(),
              p.attribution(),
              SparseNameArtifactDefinition.PREFIX_FIELDS);
      var interpretation =
          new SparseNameArtifactDefinition.Interpretation(
              DECODER_REVISION,
              INDEX_REVISION,
              header.tableHash(),
              header.layoutHash(),
              0,
              prefixBytes,
              section.fileOffset(),
              section.offsetRecordsEndOffset() - section.fileOffset(),
              section.offsetRecordsEndOffset(),
              suffixBytes,
              schemaIdentity,
              locale,
              "physical-only-copies-excluded-v1",
              ContentRepresentation.VERIFIER_SEMANTICS
                  + ";"
                  + representation.scope().name()
                  + ";"
                  + WINDOWS_REVISION);
      var definition =
          new SparseNameArtifactDefinition(
              List.of(representation.encoded().object()), interpretation);
      var windows =
          List.of(
              new ItemProjectionEvidence.DecodedWindow(0, prefixBytes),
              new ItemProjectionEvidence.DecodedWindow(
                  section.fileOffset(), interpretation.recordAreaBytes()),
              new ItemProjectionEvidence.DecodedWindow(
                  section.offsetRecordsEndOffset(), suffixBytes));
      var result =
          new Opened(
              lease,
              association,
              definition,
              List.copyOf(copies),
              chunks,
              projector,
              recipe,
              metadata,
              windows,
              physicalVerifiedAt);
      lease = null;
      return result;
    } catch (Exception e) {
      throw classify(e);
    } finally {
      if (lease != null) lease.close();
    }
  }

  @Override
  public VerificationLease verify(PreparedSparseNames names, CancellationToken token) {
    Objects.requireNonNull(names);
    Objects.requireNonNull(token);
    var opened =
        load(names.evidence().association().descriptor(), names.locale(), VERIFY_LIMITS, token);
    try {
      var e = names.evidence();
      var s = opened.metadata.sections().getFirst();
      if (!opened.definition.equals(names.definition())
          || !opened.association.equals(e.association())
          || !opened.lease.object().equals(e.localVerification().object())
          || !e.localVerification().verifierRevision().equals("sha256-complete-encoded-v1")
          || !e.windows().equals(opened.windows)
          || !e.chunks().equals(opened.chunks.expected(opened.windows))
          || e.fullDecoded().isPresent()
          || names.coverage().physicalRows() != s.recordCount()
          || names.coverage().unindexedCopyDeclarations() != s.copyTableCount()
          || names.coverage().excludedSections() != opened.metadata.sections().size() - 1)
        throw failure(
            CORRUPT, "Trusted names manifest differs from exact current source/structural recipe");
      // No record-area decode or fresh decoded-window attestation. The backend must already have
      // authenticated this persisted manifest; this method does not authenticate arbitrary DTOs.
      return opened;
    } catch (RuntimeException e) {
      opened.close();
      throw e;
    }
  }

  private static final class Opened implements OpenedNames {
    final ObjectReadLease lease;
    final SelectedFileAssociation association;
    final SparseNameArtifactDefinition definition;
    final List<CopyDeclaration> copies;
    final Chunks chunks;
    final SparsePhysicalNameProjector projector;
    final SparsePhysicalNameProjector.Prepared recipe;
    final Wdc5Metadata metadata;
    final List<ItemProjectionEvidence.DecodedWindow> windows;
    final java.time.Instant physicalVerifiedAt;
    boolean closed, used;

    Opened(
        ObjectReadLease lease,
        SelectedFileAssociation association,
        SparseNameArtifactDefinition definition,
        List<CopyDeclaration> copies,
        Chunks chunks,
        SparsePhysicalNameProjector projector,
        SparsePhysicalNameProjector.Prepared recipe,
        Wdc5Metadata metadata,
        List<ItemProjectionEvidence.DecodedWindow> windows,
        java.time.Instant physicalVerifiedAt) {
      this.lease = lease;
      this.association = association;
      this.definition = definition;
      this.copies = copies;
      this.chunks = chunks;
      this.projector = projector;
      this.recipe = recipe;
      this.metadata = metadata;
      this.windows = windows;
      this.physicalVerifiedAt = physicalVerifiedAt;
    }

    public SparseNameArtifactDefinition definition() {
      ensureOpen();
      return definition;
    }

    public List<CopyDeclaration> copies() {
      ensureOpen();
      return copies;
    }

    public PreparedSparseNames project(RowSink sink, CancellationToken token) {
      ensureOpen();
      Objects.requireNonNull(sink);
      Objects.requireNonNull(token);
      if (used) throw failure(INVALID_REQUEST, "Sparse projection is single-use");
      used = true;
      try {
        var i = definition.interpretation();
        var completion =
            projector.project(
                recipe,
                chunks.stream(i.recordAreaOffset(), i.recordAreaBytes(), token),
                row ->
                    sink.accept(
                        new DisplayCandidate(
                            row.displayCandidate(),
                            row.recordId(),
                            row.physicalOrdinal(),
                            row.sectionOrdinal(),
                            row.decodedOffset(),
                            row.recordBytes(),
                            row.prefixBytes())),
                token::isCancellationRequested);
        token.throwIfCancellationRequested();
        var expected = chunks.expected(windows);
        if (!chunks.actual().equals(expected))
          throw failure(CORRUPT, "Completed names windows lack exact consumed chunk proof");
        var evidence =
            new ItemProjectionEvidence(
                association,
                new ItemProjectionEvidence.LocalVerification(
                    lease.object(), physicalVerifiedAt, "sha256-complete-encoded-v1"),
                chunks.actual(),
                windows,
                Optional.empty());
        var coverage =
            new PreparedSparseNames.Coverage(
                completion.sectionOrdinal(),
                completion.physicalRows(),
                completion.unindexedCopyDeclarations(),
                completion.excludedSections(),
                completion.projectedUtf8Bytes());
        return new PreparedSparseNames(definition, evidence, coverage);
      } catch (Exception e) {
        throw classify(e);
      }
    }

    void ensureOpen() {
      if (closed) throw failure(CLOSED, "Sparse source lease is closed");
    }

    public void close() {
      if (!closed) {
        closed = true;
        chunks.release();
        lease.close();
      }
    }
  }

  /**
   * One retained decoded chunk; the protocol API's defensive return copy is transient and bounded.
   */
  private static final class Chunks {
    byte[] encoded;
    final BlteChunkPlanner planner;
    final BlteChunkPlanner.AuthenticatedPlan plan;
    final long workLimit;
    final Map<Integer, ItemProjectionEvidence.ChunkAttestation> consumed = new TreeMap<>();
    long work;
    int cached = -1;
    byte[] decoded;

    Chunks(
        byte[] encoded,
        BlteChunkPlanner planner,
        BlteChunkPlanner.AuthenticatedPlan plan,
        long limit) {
      this.encoded = encoded;
      this.planner = planner;
      this.plan = plan;
      workLimit = limit;
    }

    byte[] chunk(int ordinal, CancellationToken token) throws BlteException {
      token.throwIfCancellationRequested();
      if (cached == ordinal) return decoded;
      var c = plan.chunks().get(ordinal);
      if (c.decodedBytes() > workLimit - work)
        throw failure(LIMIT_EXCEEDED, "Actual Sparse decoded work exceeds extraction bound");
      work += c.decodedBytes();
      decoded = null;
      cached = -1;
      byte[] bytes =
          Arrays.copyOfRange(
              encoded,
              Math.toIntExact(c.encodedOffset()),
              Math.toIntExact(c.encodedOffset() + c.encodedBytes()));
      byte[] value =
          planner.decodeSelected(plan, c, bytes, token::isCancellationRequested).decodedBytes();
      consumed.put(ordinal, attestation(c));
      decoded = value;
      cached = ordinal;
      return value;
    }

    byte[] window(long offset, int length, CancellationToken token) throws IOException {
      byte[] result = new byte[length];
      var input = stream(offset, length, token);
      int at = 0;
      while (at < length) {
        int n = input.read(result, at, length - at);
        if (n < 0) throw failure(CORRUPT, "Truncated Sparse decoded window");
        at += n;
      }
      if (input.read() != -1)
        throw failure(CORRUPT, "Sparse decoded window exceeded requested extent");
      return result;
    }

    InputStream stream(long offset, long length, CancellationToken token) {
      if (offset < 0 || length < 0 || offset > plan.wholeDecodedBytes() - length)
        throw failure(CORRUPT, "Required Sparse decoded window is out of bounds");
      int first = 0;
      while (first < plan.chunks().size()
          && plan.chunks().get(first).decodedOffset() + plan.chunks().get(first).decodedBytes()
              <= offset) first++;
      final int start = first;
      return new InputStream() {
        long cursor = offset, remaining = length;
        int ordinal = start;

        public int read() throws IOException {
          byte[] one = new byte[1];
          return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        public int read(byte[] target, int at, int count) throws IOException {
          Objects.checkFromIndexSize(at, count, target.length);
          if (count == 0) return 0;
          token.throwIfCancellationRequested();
          if (remaining == 0) return -1;
          while (ordinal < plan.chunks().size()
              && cursor
                  >= plan.chunks().get(ordinal).decodedOffset()
                      + plan.chunks().get(ordinal).decodedBytes()) ordinal++;
          if (ordinal >= plan.chunks().size())
            throw failure(CORRUPT, "Sparse window has no covering chunk");
          var c = plan.chunks().get(ordinal);
          if (cursor < c.decodedOffset()) throw failure(CORRUPT, "Sparse window has a decoded gap");
          try {
            byte[] value = chunk(ordinal, token);
            int within = Math.toIntExact(cursor - c.decodedOffset());
            int take = (int) Math.min(Math.min(count, remaining), value.length - within);
            System.arraycopy(value, within, target, at, take);
            cursor += take;
            remaining -= take;
            return take;
          } catch (BlteException e) {
            throw classify(e);
          }
        }
      };
    }

    List<ItemProjectionEvidence.ChunkAttestation> actual() {
      return List.copyOf(consumed.values());
    }

    List<ItemProjectionEvidence.ChunkAttestation> expected(
        List<ItemProjectionEvidence.DecodedWindow> windows) {
      var result = new ArrayList<ItemProjectionEvidence.ChunkAttestation>();
      for (var c : plan.chunks())
        for (var w : windows)
          if (c.decodedBytes() > 0
              && c.decodedOffset() < w.offset() + w.length()
              && c.decodedOffset() + c.decodedBytes() > w.offset()) {
            result.add(attestation(c));
            break;
          }
      return List.copyOf(result);
    }

    private static ItemProjectionEvidence.ChunkAttestation attestation(BlteChunkPlanner.Chunk c) {
      return new ItemProjectionEvidence.ChunkAttestation(
          c.index(),
          c.encodedOffset(),
          c.encodedBytes(),
          HexFormat.of().formatHex(c.encodedMd5()),
          c.decodedOffset(),
          c.decodedBytes(),
          WINDOWS_REVISION);
    }

    void release() {
      encoded = null;
      decoded = null;
      cached = -1;
    }
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ItemNamesException failure(ItemNamesException.Code code, String message) {
    return new ItemNamesException(code, message);
  }

  private static RuntimeException classify(Exception e) {
    if (e instanceof ItemNamesException x) return x;
    if (e instanceof CancellationException x) return x;
    if (e instanceof SelectedFileException x)
      return new ItemNamesException(
          switch (x.code()) {
            case MISSING_LOCAL, NOT_FOUND -> MISSING_LOCAL;
            case INTEGRITY_FAILURE, INVALID_PLAN -> CORRUPT;
            case UNSUPPORTED -> UNSUPPORTED;
            case BUDGET_EXCEEDED -> LIMIT_EXCEEDED;
            case CANCELLED ->
                throw new CancellationException("Sparse association replay cancelled");
            case CLOSED -> CLOSED;
            case BUSY, CAPACITY_EXCEEDED -> BUSY;
            default -> STORAGE_UNAVAILABLE;
          },
          x.getMessage(),
          x);
    if (e instanceof ObjectStoreException x)
      return new ItemNamesException(
          switch (x.code()) {
            case MISSING_OBJECT -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> CORRUPT;
            case LIMIT_EXCEEDED -> LIMIT_EXCEEDED;
            case CLOSED -> CLOSED;
            case BUSY -> BUSY;
            default -> STORAGE_UNAVAILABLE;
          },
          x.getMessage(),
          x);
    if (e instanceof EncodedBlteException x) return protocolFailure(x.reason().name(), x);
    if (e instanceof BlteException x) return protocolFailure(x.reason().name(), x);
    if (e instanceof Db2HeaderException x) return protocolFailure(x.reason().name(), x);
    if (e instanceof SchemaException x) return protocolFailure(x.reason().name(), x);
    if (e instanceof SparseNameProjectionException x) return protocolFailure(x.reason().name(), x);
    if (e instanceof IllegalArgumentException)
      return new ItemNamesException(CORRUPT, "Sparse structural/proof facts are inconsistent", e);
    return new ItemNamesException(
        STORAGE_UNAVAILABLE, "Retained Sparse source operation failed", e);
  }

  private static RuntimeException protocolFailure(String reason, Exception cause) {
    if (reason.equals("CANCELLED")) return new CancellationException("Sparse projection cancelled");
    return new ItemNamesException(
        switch (reason) {
          case "LIMIT" -> LIMIT_EXCEEDED;
          case "UNSUPPORTED" -> UNSUPPORTED;
          case "INCOMPATIBLE" -> INCOMPATIBLE;
          case "IO_FAILURE" -> STORAGE_UNAVAILABLE;
          default -> CORRUPT;
        },
        cause.getMessage(),
        cause);
  }
}
