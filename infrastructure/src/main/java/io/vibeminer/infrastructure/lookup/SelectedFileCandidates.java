package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.application.selectedfile.SelectedFileException.Code.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.CapturedBuildSource;
import io.vibeminer.protocol.tact.config.*;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Local exact-root replay followed by bounded authenticated selected EN metadata. */
final class SelectedFileCandidates {
  static final String ASSOCIATION_REVISION = "root-variant+selected-en-pages-v1";
  private final ObjectStore store;
  private final OfficialFileLookupGateway roots;
  private final LookupTransport transport;

  SelectedFileCandidates(
      ObjectStore store, OfficialFileLookupGateway roots, LookupTransport transport) {
    this.store = store;
    this.roots = roots;
    this.transport = transport;
  }

  TactConfig replay(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      FileVariantAssociation variant,
      SelectedFileBudgets budgets,
      CancellationToken cancellation)
      throws Exception {
    var maximum = budgets.lookup().maximumConfigurationBytes();
    if (maximum < 1 || maximum >= Integer.MAX_VALUE)
      throw new SelectedFileException(
          BUDGET_EXCEEDED, "Configuration read bound cannot be represented safely");
    if (!build.equals(variant.build())
        || !variant.rootSource().equals(root.source())
        || !build.productId().equals("wow"))
      throw new SelectedFileException(
          INVALID_PLAN, "Selected variant belongs to another exact build/root");
    var result =
        roots.lookupRetained(
            build,
            configuration,
            root,
            Set.of(variant.fileDataId()),
            budgets.lookup(),
            cancellation);
    boolean found = result.variants().stream().anyMatch(v -> same(v, variant));
    if (!found)
      throw new SelectedFileException(
          INVALID_PLAN, "Selected complete raw variant is not in retained root");

    if (configuration.object().length() > maximum)
      throw new SelectedFileException(BUDGET_EXCEEDED, "Retained configuration exceeds budget");
    try (var lease = store.openRead(configuration.object().digest(), cancellation)) {
      if (!lease.object().equals(configuration.object()))
        throw new SelectedFileException(INTEGRITY_FAILURE, "Configuration receipt changed");
      byte[] bytes = lease.stream().readNBytes((int) maximum + 1);
      return new TactConfigParser()
          .parse(
              bytes,
              build.buildConfig(),
              new TactConfigParser.Limits(
                  (int) maximum, 20000, (int) maximum, 20000, 2L << 30, 2L << 30),
              cancellation::isCancellationRequested);
    }
  }

  CandidatePlan resolve(
      BuildSnapshot build,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      FileVariantAssociation variant,
      SelectedFileBudgets budgets,
      CancellationToken cancellation)
      throws Exception {
    var config = replay(build, configuration, root, variant, budgets, cancellation);
    var b = budgets.lookup();
    var work = new LookupWork(b, cancellation, true);
    var encoding = config.encodingBootstrap();
    var remote =
        new RemoteBlteObject(
            CapturedBuildSource.resolve(build),
            encoding.encodingKey(),
            encoding.encodedSize(),
            encoding.decodedSize(),
            transport,
            b,
            work,
            true);
    var limits =
        new IndexedEncodingParser.Limits(
            100000,
            b.maximumIndexBytes(),
            b.maximumPageBytes(),
            65536,
            Math.min(255, b.maximumCandidates()));
    var parser = new IndexedEncodingParser();
    var layout = parser.parseHeader(remote.authenticatedWindow(0, 22), limits);
    if (layout.minimumDecodedBytes() > encoding.decodedSize())
      throw new SelectedFileException(INTEGRITY_FAILURE, "EN geometry exceeds pinned source");
    var contents =
        index(
            parser,
            layout,
            IndexedEncodingParser.Table.CONTENT,
            remote,
            limits,
            b.maximumIndexBytes(),
            work::cancelled);
    var encodings =
        index(
            parser,
            layout,
            IndexedEncodingParser.Table.ENCODING,
            remote,
            limits,
            b.maximumIndexBytes(),
            work::cancelled);
    var page =
        contents
            .pageFor(variant.contentKey())
            .orElseThrow(
                () ->
                    new SelectedFileException(
                        UNAVAILABLE, "Selected CKey has no supported EN page"));
    var entry =
        parser
            .lookupContent(
                contents,
                page,
                remote.selectedPage(page.decodedOffset(), page.length()),
                variant.contentKey(),
                limits,
                work::cancelled)
            .orElseThrow(
                () ->
                    new SelectedFileException(
                        UNAVAILABLE, "Selected CKey absent from authenticated EN scope"));
    var candidates = new ArrayList<EncodedCandidate>();
    for (String key : entry.encodingKeys()) {
      work.check();
      var selected = encodings.pageFor(key);
      var value =
          selected.isEmpty()
              ? Optional.<IndexedEncodingParser.EncodedMetadata>empty()
              : parser.lookupEncoding(
                  encodings,
                  selected.get(),
                  remote.selectedPage(selected.get().decodedOffset(), selected.get().length()),
                  key,
                  limits,
                  work::cancelled);
      candidates.add(
          new EncodedCandidate(
              key,
              value.isPresent() ? OptionalLong.of(value.get().encodedSize()) : OptionalLong.empty(),
              value.isPresent() ? OptionalLong.of(value.get().specIndex()) : OptionalLong.empty()));
    }
    var bootstrap =
        new ResolvedSourceIdentity(
            "tact.encoding.bootstrap", encoding.encodingKey(), "blte-authenticated-selected-en-v1");
    // Bind the authenticated declaration and every alternative, including unknown facts.
    String digest =
        proofDigest(
            variant,
            configuration,
            root,
            bootstrap,
            OptionalLong.of(entry.decodedSize()),
            candidates);
    var proof =
        new FileAssociationProof(
            root.source(),
            bootstrap,
            ASSOCIATION_REVISION,
            digest,
            OptionalLong.of(entry.decodedSize()),
            candidates);
    return new CandidatePlan(
        UUID.randomUUID().toString(), variant, configuration, root, proof, candidates, budgets);
  }

  static String proofDigest(
      FileVariantAssociation variant,
      VerifiedSourceAlias configuration,
      VerifiedSourceAlias root,
      ResolvedSourceIdentity bootstrap,
      OptionalLong declaredDecodedBytes,
      List<EncodedCandidate> candidates)
      throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeUTF(ASSOCIATION_REVISION);
    out.writeUTF(
        new SelectedFileDescriptor(
                variant,
                candidates.getFirst().encodingKey(),
                ContentRepresentation.VERIFIER_SEMANTICS)
            .role());
    out.writeUTF(configuration.source().namespace());
    out.writeUTF(configuration.source().immutableSourceKey());
    out.writeUTF(configuration.source().verificationSemantics());
    out.writeUTF(configuration.object().digest().value());
    out.writeUTF(root.object().digest().value());
    out.writeUTF(bootstrap.namespace());
    out.writeUTF(bootstrap.immutableSourceKey());
    out.writeUTF(bootstrap.verificationSemantics());
    out.writeBoolean(declaredDecodedBytes.isPresent());
    if (declaredDecodedBytes.isPresent()) out.writeLong(declaredDecodedBytes.getAsLong());
    out.writeInt(candidates.size());
    for (var candidate : candidates) {
      out.writeUTF(candidate.encodingKey());
      out.writeBoolean(candidate.encodedBytes().isPresent());
      if (candidate.encodedBytes().isPresent()) out.writeLong(candidate.encodedBytes().getAsLong());
      out.writeBoolean(candidate.specificationIndex().isPresent());
      if (candidate.specificationIndex().isPresent())
        out.writeLong(candidate.specificationIndex().getAsLong());
    }
    out.flush();
    return sha256(bytes.toByteArray());
  }

  private static IndexedEncodingParser.PageIndex index(
      IndexedEncodingParser parser,
      IndexedEncodingParser.Layout layout,
      IndexedEncodingParser.Table table,
      RemoteBlteObject remote,
      IndexedEncodingParser.Limits limits,
      long maximum,
      java.util.function.BooleanSupplier cancelled)
      throws Exception {
    long count = Math.multiplyExact(layout.pageCount(table), 32);
    if (count > maximum)
      throw new SelectedFileException(BUDGET_EXCEEDED, "EN index exceeds selected metadata bounds");
    return parser.parseIndex(
        layout,
        table,
        remote.authenticatedWindow(layout.indexOffset(table), (int) count),
        limits,
        cancelled);
  }

  private static boolean same(RootVariant v, FileVariantAssociation s) {
    return v.fileDataId() == s.fileDataId()
        && v.contentKey().equals(s.contentKey())
        && v.localeFlags() == s.localeFlags()
        && v.contentFlags() == s.contentFlags()
        && v.rawFlagsFirst() == s.rawFlagsFirst()
        && v.rawFlagsSecond() == s.rawFlagsSecond()
        && v.rawFlagsThird() == s.rawFlagsThird()
        && v.nameHash().equals(s.nameHash());
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
