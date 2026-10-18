package io.vibeminer.infrastructure.item;

import static io.vibeminer.application.item.ItemProjectionException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.item.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.format.wow.item.ItemPhysicalProjector;
import io.vibeminer.format.wow.schema.SchemaSelection;
import io.vibeminer.infrastructure.schema.BundledItemSchemas;
import io.vibeminer.protocol.db2.*;
import io.vibeminer.protocol.tact.blte.*;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Offline production composition. No transport is created or invoked by this reader. */
public final class RetainedItemProjectionSourceReader implements ItemProjectionSourceReader {
  public static final String WINDOW_REVISION = "item-selected-windows-v1";
  public static final String INDEX_REVISION = "item-physical-index-v1";
  public static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;
  private final ObjectStore store;
  private final SelectedFileCatalog catalog;
  private final SelectedFileGateway gateway;
  private final Clock clock;
  private final BundledItemSchemas schemas = new BundledItemSchemas();

  public RetainedItemProjectionSourceReader(
      ObjectStore store, SelectedFileCatalog catalog, SelectedFileGateway gateway, Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.catalog = Objects.requireNonNull(catalog);
    this.gateway = Objects.requireNonNull(gateway);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public OpenedProjection open(ItemProjectionRequest request, CancellationToken token) {
    ObjectReadLease lease = null;
    try {
      Objects.requireNonNull(request);
      Objects.requireNonNull(token);
      token.throwIfCancellationRequested();
      var descriptor = request.source();
      var variant = descriptor.association();
      var b = variant.build();
      if (!b.productId().equals("wow")
          || !b.region().equals("us")
          || !b.versionLabel().equals(BundledItemSchemas.BUILD)
          || !request.locale().equals("enUS")
          || variant.fileDataId() != 841626
          || !variant.contentKey().equals("1df6b6dbae36878cea020a62b15b9bd6"))
        throw fail(UNSUPPORTED, "Source is outside reviewed Item cohort");
      var association =
          catalog
              .findOriginalAssociation(descriptor, token)
              .orElseThrow(
                  () -> fail(MISSING_LOCAL, "Exact original Item association is not retained"));
      if (!association.descriptor().role().equals(descriptor.role())
          || !association.snapshotId().equals(b.snapshotId()))
        throw fail(CORRUPT, "Retained association changed exact descriptor");
      long encodedBytes = association.representation().encoded().object().length();
      if (encodedBytes > request.limits().maxEncodedBytes() || encodedBytes > MAX_ENCODED_BYTES)
        throw fail(LIMIT_EXCEEDED, "Encoded Item source exceeds local materialization bound");
      var inspected = gateway.inspectRetained(association, SelectedFileBudgets.defaults(), token);
      if (!inspected.equals(association.representation()))
        throw fail(CORRUPT, "Offline verifier changed original source representation");
      lease = store.openRead(inspected.encoded().object().digest(), token);
      if (!lease.object().equals(inspected.encoded().object()))
        throw fail(CORRUPT, "Local physical identity changed");
      byte[] encoded;
      try (var verifier =
          new EncodedBlteInputStream(
              lease.stream(),
              descriptor.actualEncodingKey(),
              encodedBytes,
              new EncodedBlteInputStream.Limits(131072, 4096, MAX_ENCODED_BYTES, MAX_ENCODED_BYTES),
              token::isCancellationRequested)) {
        encoded = verifier.readNBytes(Math.toIntExact(encodedBytes) + 1);
        var proof = verifier.completionProof();
        if (encoded.length != encodedBytes
            || !proof.physicalSha256().equals(inspected.encoded().object().digest().value())
            || !proof.scope().name().equals(inspected.scope().name()))
          throw fail(CORRUPT, "Complete encoded proof differs from retained source");
      }
      long declaredDecoded =
          association
              .proof()
              .declaredDecodedBytes()
              .orElseThrow(() -> fail(CORRUPT, "Authenticated decoded length declaration missing"));
      if (encoded.length < 12) throw fail(CORRUPT, "Truncated BLTE source");
      long headerBytes =
          Integer.toUnsignedLong(ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).getInt(4));
      if (headerBytes == 0)
        throw fail(UNSUPPORTED, "Zero-header Item window interpretation is not admitted");
      if (headerBytes > 131072 || headerBytes > encoded.length)
        throw fail(LIMIT_EXCEEDED, "BLTE header exceeds admitted bound");
      var planner = new BlteChunkPlanner();
      var plan =
          planner.authenticate(
              Arrays.copyOf(encoded, (int) headerBytes),
              HexFormat.of().parseHex(descriptor.actualEncodingKey()),
              encodedBytes,
              declaredDecoded,
              new BlteChunkPlanner.Limits(
                  131072, 4096, MAX_ENCODED_BYTES, 128L << 20, 4 << 20, 4 << 20),
              token::isCancellationRequested);
      var windows = new Windows(encoded, planner, plan, token);
      int metadataBound = Math.min(request.limits().maxMetadataBytes(), 131072);
      if (metadataBound < 204)
        throw fail(LIMIT_EXCEEDED, "Metadata bound is shorter than WDC5 header");
      byte[] header = windows.read(0, 204);
      var h =
          new Wdc5HeaderInspector()
              .inspect(
                  header,
                  new Wdc5HeaderInspector.Limits(
                      metadataBound, 0xffffffffL, 128, 128, 4 << 20, 128L << 20),
                  token::isCancellationRequested);
      long metadataEnd =
          Math.addExact(
              204,
              Math.addExact(
                  Math.multiplyExact(h.sectionCount(), 40),
                  Math.multiplyExact(h.fieldCount(), 28)));
      long prefixEnd =
          Math.addExact(metadataEnd, Math.addExact(h.palletDataSize(), h.commonDataSize()));
      if (prefixEnd > metadataBound || h.palletDataSize() > request.limits().maxPalletBytes())
        throw fail(LIMIT_EXCEEDED, "Item prefix/pallet exceeds explicit local bounds");
      byte[] prefix = windows.read(0, Math.toIntExact(prefixEnd));
      var metadata =
          new Wdc5MetadataInspector()
              .inspect(
                  prefix,
                  declaredDecoded,
                  new Wdc5MetadataInspector.Limits(
                      metadataBound, 128L << 20, 0xffffffffL, 128, 128, 4 << 20, metadataBound),
                  token::isCancellationRequested);
      var schema =
          schemas.select(
              "wow",
              b.versionLabel(),
              BundledItemSchemas.Table.ITEM,
              new SchemaSelection.HeaderFacts(
                  h.tableHash(), h.layoutHash(), h.fieldCount(), h.flags()),
              token::isCancellationRequested);
      var projector = new ItemPhysicalProjector();
      var l = request.limits();
      var recipe =
          projector.prepare(
              metadata,
              schema,
              prefix,
              0,
              new ItemPhysicalProjector.Limits(
                  l.maxSectionBytes(),
                  l.maxPhysicalRows(),
                  l.maxCopyDeclarations(),
                  l.maxMetadataBytes(),
                  l.maxPalletBytes()),
              token::isCancellationRequested);
      var section = metadata.sections().getFirst();
      int sectionBytes = Math.toIntExact(section.declaredEndOffset() - section.fileOffset());
      if (sectionBytes > l.maxSectionBytes())
        throw fail(LIMIT_EXCEEDED, "Section exceeds explicit local bounds");
      byte[] span = windows.read(section.fileOffset(), sectionBytes);
      var index =
          new FixedSectionIndexParser()
              .parse(
                  span,
                  h,
                  section,
                  new FixedSectionIndexParser.Limits(
                      l.maxSectionBytes(), l.maxPhysicalRows(), l.maxCopyDeclarations()),
                  token::isCancellationRequested);
      var physical = new HashSet<>(index.recordIds());
      var targets = new HashSet<Long>();
      var copies = new ArrayList<CopyDeclaration>();
      for (var copy : index.copies()) {
        token.throwIfCancellationRequested();
        if (copy.targetId() == 0
            || copy.sourceId() == 0
            || physical.contains(copy.targetId())
            || !targets.add(copy.targetId()))
          throw fail(CORRUPT, "Copy declarations are ambiguous or overlap physical IDs");
        copies.add(new CopyDeclaration(copy.targetId(), copy.sourceId()));
      }
      var bindings =
          List.of(
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.CLASS_ID, 0, 32, ItemArtifactDefinition.Sign.SIGNED),
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.SUBCLASS_ID,
                  1,
                  8,
                  ItemArtifactDefinition.Sign.UNSIGNED),
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.INVENTORY_TYPE,
                  3,
                  8,
                  ItemArtifactDefinition.Sign.SIGNED),
              new ItemArtifactDefinition.FieldBinding(
                  ItemArtifactDefinition.Field.ICON_FILE_DATA_ID,
                  6,
                  32,
                  ItemArtifactDefinition.Sign.SIGNED));
      String selectionDigest =
          sha(
              ("wow\n" + b.versionLabel() + "\nItem\n50238EC2\n996192AA\n15\nnoninline-fixed-v1")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var attribution = schema.provenance();
      var schemaIdentity =
          new ItemArtifactDefinition.Schema(
              attribution.provider(),
              attribution.revision(),
              "definitions/Item.dbd",
              attribution.sha256(),
              selectionDigest,
              attribution.license(),
              attribution.attribution(),
              bindings);
      var definition =
          new ItemArtifactDefinition(
              List.of(inspected.encoded().object()),
              new ItemArtifactDefinition.Interpretation(
                  recipe.projectionRevision() + ";wdc5-metadata-v1;fixed-index-v1;field-value-v1",
                  INDEX_REVISION,
                  h.tableHash(),
                  h.layoutHash(),
                  0,
                  section.fileOffset(),
                  sectionBytes,
                  schemaIdentity,
                  request.locale(),
                  "physical-only-copies-excluded-v1",
                  ContentRepresentation.VERIFIER_SEMANTICS
                      + ";"
                      + inspected.scope().name()
                      + ";"
                      + WINDOW_REVISION));
      var evidence =
          new ItemProjectionEvidence(
              association,
              new ItemProjectionEvidence.LocalVerification(
                  inspected.encoded().object(), clock.instant(), "sha256-complete-encoded-v1"),
              windows.attestations(),
              List.of(
                  new ItemProjectionEvidence.DecodedWindow(0, prefixEnd),
                  new ItemProjectionEvidence.DecodedWindow(section.fileOffset(), sectionBytes)),
              Optional.empty());
      var opened =
          new Opened(
              lease,
              definition,
              evidence,
              List.copyOf(copies),
              projector,
              recipe,
              span,
              new PreparedItemProjection.Coverage(
                  0,
                  section.recordCount(),
                  section.copyTableCount(),
                  metadata.sections().size() - 1));
      lease = null;
      return opened;
    } catch (Exception failure) {
      throw classify(failure);
    } finally {
      if (lease != null) lease.close();
    }
  }

  @Override
  public VerificationLease verify(PreparedItemProjection handle, CancellationToken token) {
    var opened =
        open(
            new ItemProjectionRequest(
                handle.evidence().association().descriptor(),
                handle.locale(),
                new ItemProjectionLimits(
                    MAX_ENCODED_BYTES, 4 << 20, 1_000_000, 1_000_000, 131072, 131072)),
            token);
    try {
      var actual = opened.evidence();
      var expected = handle.evidence();
      if (!((Opened) opened).declaredCoverage.equals(handle.coverage())
          || !opened.definition().equals(handle.definition())
          || !actual.association().equals(expected.association())
          || !actual.localVerification().object().equals(expected.localVerification().object())
          || !actual
              .localVerification()
              .verifierRevision()
              .equals(expected.localVerification().verifierRevision())
          || !actual.chunks().equals(expected.chunks())
          || !actual.windows().equals(expected.windows())
          || !expected.fullDecoded().isEmpty())
        throw fail(
            CORRUPT, "Prepared interpretation/window proof differs from current retained source");
      return opened;
    } catch (RuntimeException failure) {
      opened.close();
      throw failure;
    }
  }

  private static final class Opened implements OpenedProjection {
    final ObjectReadLease lease;
    final ItemArtifactDefinition definition;
    final ItemProjectionEvidence evidence;
    final List<CopyDeclaration> copies;
    final ItemPhysicalProjector projector;
    final ItemPhysicalProjector.Prepared recipe;
    final byte[] span;
    final PreparedItemProjection.Coverage declaredCoverage;
    boolean closed;

    Opened(
        ObjectReadLease l,
        ItemArtifactDefinition d,
        ItemProjectionEvidence e,
        List<CopyDeclaration> c,
        ItemPhysicalProjector p,
        ItemPhysicalProjector.Prepared r,
        byte[] s,
        PreparedItemProjection.Coverage coverage) {
      lease = l;
      definition = d;
      evidence = e;
      copies = c;
      projector = p;
      recipe = r;
      span = s;
      declaredCoverage = coverage;
    }

    public ItemArtifactDefinition definition() {
      ensureOpen();
      return definition;
    }

    public ItemProjectionEvidence evidence() {
      ensureOpen();
      return evidence;
    }

    public List<CopyDeclaration> copies() {
      ensureOpen();
      return copies;
    }

    public PreparedItemProjection.Coverage project(RowSink sink, CancellationToken token) {
      ensureOpen();
      try {
        var completion =
            projector.project(
                recipe,
                span,
                row -> {
                  var values = new ArrayList<ItemPhysicalRow.IntegerCandidate>();
                  for (int i = 0; i < 4; i++) {
                    var v = row.values().get(i);
                    values.add(
                        new ItemPhysicalRow.IntegerCandidate(
                            ItemArtifactDefinition.Field.values()[i],
                            v.raw().rawBits(),
                            v.raw().bitWidth(),
                            v.raw().signedPhysicalValue().isPresent()
                                ? ItemPhysicalRow.PhysicalInterpretation.SIGNED_TWOS_COMPLEMENT
                                : ItemPhysicalRow.PhysicalInterpretation.UNSIGNED_BITS,
                            v.candidateValue(),
                            v.candidateBits(),
                            ItemArtifactDefinition.Sign.valueOf(v.candidateSign().name())));
                  }
                  sink.accept(
                      new ItemPhysicalRow(
                          row.recordId(), row.physicalOrdinal(), row.sectionOrdinal(), values));
                },
                token::isCancellationRequested);
        return new PreparedItemProjection.Coverage(
            completion.sectionOrdinal(),
            completion.physicalRows(),
            completion.unindexedCopyDeclarations(),
            completion.excludedSections());
      } catch (Exception failure) {
        throw classify(failure);
      }
    }

    private void ensureOpen() {
      if (closed) throw fail(CLOSED, "Item source lease closed");
    }

    public void close() {
      if (!closed) {
        closed = true;
        lease.close();
      }
    }
  }

  private static final class Windows {
    final byte[] encoded;
    final BlteChunkPlanner planner;
    final BlteChunkPlanner.AuthenticatedPlan plan;
    final CancellationToken token;
    final Map<Integer, ItemProjectionEvidence.ChunkAttestation> consumed = new TreeMap<>();

    Windows(
        byte[] encoded,
        BlteChunkPlanner planner,
        BlteChunkPlanner.AuthenticatedPlan plan,
        CancellationToken token) {
      this.encoded = encoded;
      this.planner = planner;
      this.plan = plan;
      this.token = token;
    }

    byte[] read(long offset, int length) throws IOException, BlteException {
      if (offset < 0 || length < 0 || offset > plan.wholeDecodedBytes() - length)
        throw fail(CORRUPT, "Required window exceeds declared decoded source");
      var output = new byte[length];
      long covered = offset, end = offset + length;
      for (var chunk : plan.chunks()) {
        if (chunk.decodedOffset() + chunk.decodedBytes() <= covered) continue;
        if (chunk.decodedOffset() >= end) break;
        if (chunk.decodedOffset() > covered)
          throw fail(CORRUPT, "Required decoded window has a gap");
        token.throwIfCancellationRequested();
        byte[] block =
            Arrays.copyOfRange(
                encoded,
                Math.toIntExact(chunk.encodedOffset()),
                Math.toIntExact(chunk.encodedOffset() + chunk.encodedBytes()));
        byte[] decoded =
            planner
                .decodeSelected(plan, chunk, block, token::isCancellationRequested)
                .decodedBytes();
        long takeEnd = Math.min(end, chunk.decodedOffset() + chunk.decodedBytes());
        System.arraycopy(
            decoded,
            Math.toIntExact(covered - chunk.decodedOffset()),
            output,
            Math.toIntExact(covered - offset),
            Math.toIntExact(takeEnd - covered));
        consumed.put(
            chunk.index(),
            new ItemProjectionEvidence.ChunkAttestation(
                chunk.index(),
                chunk.encodedOffset(),
                chunk.encodedBytes(),
                HexFormat.of().formatHex(chunk.encodedMd5()),
                chunk.decodedOffset(),
                chunk.decodedBytes(),
                WINDOW_REVISION));
        covered = takeEnd;
      }
      if (covered != end) throw fail(CORRUPT, "Truncated required window");
      return output;
    }

    List<ItemProjectionEvidence.ChunkAttestation> attestations() {
      return List.copyOf(consumed.values());
    }
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static ItemProjectionException fail(ItemProjectionException.Code c, String m) {
    return new ItemProjectionException(c, m);
  }

  private static RuntimeException classify(Exception e) {
    if (e instanceof ItemProjectionException x) return x;
    if (e instanceof CancellationException x) return x;
    if (e instanceof EncodedBlteException x)
      return new ItemProjectionException(
          switch (x.reason()) {
            case LIMIT -> LIMIT_EXCEEDED;
            case CANCELLED ->
                throw new CancellationException("Encoded Item verification cancelled");
            case UNSUPPORTED -> UNSUPPORTED;
            default -> CORRUPT;
          },
          x.getMessage(),
          x);
    if (e instanceof SelectedFileException x)
      return new ItemProjectionException(
          switch (x.code()) {
            case MISSING_LOCAL, NOT_FOUND -> MISSING_LOCAL;
            case INTEGRITY_FAILURE -> CORRUPT;
            case UNSUPPORTED -> UNSUPPORTED;
            case CAPACITY_EXCEEDED, BUSY -> BUSY;
            case CANCELLED ->
                throw new CancellationException("Offline Item verification cancelled");
            case BUDGET_EXCEEDED -> LIMIT_EXCEEDED;
            case CLOSED -> CLOSED;
            default -> STORAGE_UNAVAILABLE;
          },
          x.getMessage(),
          x);
    if (e instanceof ObjectStoreException x)
      return new ItemProjectionException(
          x.code() == ObjectStoreException.Code.MISSING_OBJECT
              ? MISSING_LOCAL
              : x.code() == ObjectStoreException.Code.INTEGRITY_FAILURE
                  ? CORRUPT
                  : STORAGE_UNAVAILABLE,
          x.getMessage(),
          x);
    if (e instanceof BlteException x)
      return new ItemProjectionException(
          switch (x.reason()) {
            case LIMIT -> LIMIT_EXCEEDED;
            case CANCELLED -> throw new CancellationException("Item window cancelled");
            case UNSUPPORTED -> UNSUPPORTED;
            default -> CORRUPT;
          },
          x.getMessage(),
          x);
    if (e instanceof io.vibeminer.format.wow.schema.SchemaException x)
      return new ItemProjectionException(
          switch (x.reason()) {
            case CANCELLED -> throw new CancellationException("Item schema selection cancelled");
            case LIMIT -> LIMIT_EXCEEDED;
            case UNSUPPORTED -> UNSUPPORTED;
            default -> INCOMPATIBLE;
          },
          x.getMessage(),
          x);
    if (e instanceof Db2HeaderException x)
      return new ItemProjectionException(
          switch (x.reason()) {
            case CANCELLED -> throw new CancellationException("Item metadata cancelled");
            case LIMIT -> LIMIT_EXCEEDED;
            case UNSUPPORTED -> UNSUPPORTED;
            default -> CORRUPT;
          },
          x.getMessage(),
          x);
    if (e instanceof io.vibeminer.format.wow.item.ItemProjectionException x)
      return new ItemProjectionException(
          switch (x.reason()) {
            case CANCELLED -> throw new CancellationException("Item projection cancelled");
            case LIMIT -> LIMIT_EXCEEDED;
            case INCOMPATIBLE -> INCOMPATIBLE;
            case UNSUPPORTED -> UNSUPPORTED;
            default -> CORRUPT;
          },
          x.getMessage(),
          x);
    return new ItemProjectionException(
        STORAGE_UNAVAILABLE, "Retained Item source operation failed", e);
  }
}
