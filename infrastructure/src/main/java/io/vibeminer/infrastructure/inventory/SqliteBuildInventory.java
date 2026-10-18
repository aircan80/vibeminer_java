package io.vibeminer.infrastructure.inventory;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildopening.BuildMetadataAdmission.Role;
import io.vibeminer.application.content.CanonicalContentRepository;
import io.vibeminer.application.inventory.ManifestReferenceResolver;
import io.vibeminer.application.inventory.ManifestReferenceResolver.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.format.wow.root.*;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.download.DownloadInventoryReader;
import io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryReader;
import io.vibeminer.protocol.tact.install.InstallManifestParser;
import java.io.*;
import java.util.*;

/** Local retained-source replay into complete all-variant membership, never a download/locator. */
public final class SqliteBuildInventory {
  public static final String REVISION = "wow-root-all-declared-inventory-v1";

  /** Reference/derived-byte ceilings apply both per generation and table-globally; no eviction. */
  public record Limits(
      long maximumReferences,
      long maximumDerivedBytes,
      int batchRows,
      long maximumBatches,
      FullRootInventory.Limits root,
      InstallManifestParser.Limits install) {
    public Limits {
      new InventoryStaging.Limits(
          maximumReferences, maximumDerivedBytes, batchRows, maximumBatches);
      Objects.requireNonNull(root);
      Objects.requireNonNull(install);
      if (install.maxBytes() > 16 * 1024 * 1024)
        throw new IllegalArgumentException("Initial IN inventory maximum is 16 MiB");
    }

    InventoryStaging.Limits staging() {
      return new InventoryStaging.Limits(
          maximumReferences, maximumDerivedBytes, batchRows, maximumBatches);
    }
  }

  /**
   * This generation represents manifest membership; payload availability and retention are
   * separate.
   */
  public record Prepared(String generation, long uniqueContentTargets, String revision) {}

  private final ObjectStore store;
  private final BuildMetadataAdmissionRepository admissions;
  private final SqliteEncodingLookupIndex encoding;
  private final CanonicalContentRepository canonical;
  private final InventoryStaging staging;
  private final ManifestReferenceResolver resolver = new ManifestReferenceResolver();

  public SqliteBuildInventory(
      SqliteCatalog catalog,
      ObjectStore store,
      BuildMetadataAdmissionRepository admissions,
      SqliteEncodingLookupIndex encoding,
      CanonicalContentRepository canonical) {
    this.store = Objects.requireNonNull(store);
    this.admissions = Objects.requireNonNull(admissions);
    this.encoding = Objects.requireNonNull(encoding);
    this.canonical = Objects.requireNonNull(canonical);
    staging = new InventoryStaging(catalog);
  }

  public Prepared prepare(
      BuildLibraryReference reference,
      String locale,
      String scopeRevision,
      MetadataStreamBudgets budgets,
      SqliteEncodingLookupIndex.Limits encodingLimits,
      Limits limits,
      CancellationToken cancellation)
      throws IOException {
    Objects.requireNonNull(cancellation);
    long start = System.nanoTime();
    long duration = budgets.timeout().toNanos();
    CancellationToken token =
        () -> cancellation.isCancellationRequested() || System.nanoTime() - start >= duration;
    token.throwIfCancellationRequested();
    var facts =
        admissions
            .find(reference, locale, scopeRevision, token)
            .orElseThrow(() -> new IllegalStateException("Complete metadata admission required"));
    long work = 0, grammarWork = 0;
    for (var m : facts.metadata()) {
      long bytes = m.proof().decodedAttestation().orElseThrow().actualDecodedBytes();
      work = Math.addExact(work, bytes);
      grammarWork =
          Math.addExact(
              grammarWork,
              MetadataStreamBudgets.requiredGrammarWork(
                  m.proof().structuralAttestation().orElseThrow().kind(), bytes));
    }
    if (work > budgets.maximumDecodedWorkBytes() || grammarWork > budgets.maximumGrammarWorkBytes())
      throw new IllegalArgumentException("Aggregate inventory replay work exceeds budget");
    var leases = new ArrayList<ObjectReadLease>();
    String ownGeneration = null;
    try (var en = encoding.open(reference, locale, scopeRevision, budgets, encodingLimits, token)) {
      var sources =
          facts.metadata().stream()
              .filter(m -> m.role() != Role.ENCODING)
              .map(
                  m ->
                      new InventoryStaging.Source(
                          m.role().name(), CanonicalContentRecords.encode(m.proof())))
              .toList();
      var existing =
          staging.findReady(
              reference.snapshotId(),
              BuildMetadataAdmissionRepository.closureId(facts),
              en.artifactId(),
              en.generation(),
              REVISION,
              sources,
              c -> {
                admissions.validateInTransaction(c, facts, token);
                en.validateInTransaction(c, token);
                requireCanonicalCompatibility(
                    c,
                    reference.snapshotId(),
                    BuildMetadataAdmissionRepository.closureId(facts),
                    en.artifactId(),
                    en.generation());
              },
              token);
      if (existing.isPresent())
        return new Prepared(existing.get().id(), existing.get().members(), REVISION);
      String id =
          staging.begin(
              reference.snapshotId(),
              BuildMetadataAdmissionRepository.closureId(facts),
              en.artifactId(),
              en.generation(),
              REVISION,
              sources,
              limits.staging(),
              token);
      ownGeneration = id;
      for (var source : facts.metadata()) {
        if (source.role() == Role.ENCODING) continue;
        token.throwIfCancellationRequested();
        var proof = source.proof();
        var lease = store.openRead(proof.object().digest(), token);
        leases.add(lease);
        if (!lease.object().equals(proof.object()))
          throw new IOException("Inventory source geometry changed");
        var batch = new Batch(id, source.role().name(), limits, token);
        try (var decoded =
            new StreamingDecodedBlteInputStream(
                lease.stream(),
                proof.actualEncodingKey(),
                proof.contentKey(),
                proof.object().length(),
                proof.decodedAttestation().orElseThrow().actualDecodedBytes(),
                budgets.blte(),
                token::isCancellationRequested)) {
          switch (source.role()) {
            case ROOT -> {
              new FullRootInventory()
                  .read(
                      decoded,
                      limits.root(),
                      (block, row, entry) ->
                          batch.add(
                              block,
                              entry.contentKey(),
                              OptionalLong.empty(),
                              "fdid="
                                  + entry.fileDataId()
                                  + ";locale="
                                  + entry.localeFlags()
                                  + ";flags="
                                  + entry.rawContentFlags().first()
                                  + ","
                                  + entry.rawContentFlags().second()
                                  + ","
                                  + entry.rawContentFlags().third()
                                  + ";name="
                                  + entry.nameHash().orElse("")),
                      token::isCancellationRequested);
            }
            case INSTALL -> {
              long size = proof.decodedAttestation().orElseThrow().actualDecodedBytes();
              if (size > limits.install().maxBytes())
                throw new IOException("IN source exceeds admitted materialization limit");
              byte[] bytes = decoded.readNBytes(Math.toIntExact(size));
              if (bytes.length != size || decoded.read() != -1)
                throw new IOException("IN complete source geometry mismatch");
              var manifest =
                  new InstallManifestParser()
                      .parse(bytes, limits.install(), token::isCancellationRequested);
              for (var row : manifest.entries())
                batch.add(0, row.contentKey(), OptionalLong.of(row.decodedSize()), row.name());
            }
            case DOWNLOAD -> {
              new DownloadInventoryReader()
                  .read(
                      decoded,
                      budgets.download(),
                      new DownloadInventoryReader.Consumer() {
                        public void header(DownloadInventoryReader.Header header) {}

                        public void entry(long ordinal, DownloadInventoryReader.Entry entry) {
                          batch.add(
                              0,
                              entry.encodingKey(),
                              OptionalLong.of(entry.encodedBytes()),
                              "priority="
                                  + entry.priorityRaw()
                                  + ";checksum="
                                  + entry.checksum()
                                  + ";flags="
                                  + HexFormat.of().formatHex(entry.flags()));
                        }

                        public void tag(int ordinal, DownloadInventoryReader.Tag tag) {}
                      },
                      token::isCancellationRequested);
            }
            case SIZE -> {
              new DownloadSizeInventoryReader()
                  .read(
                      decoded,
                      budgets.downloadSize(),
                      new DownloadSizeInventoryReader.Consumer() {
                        public void header(DownloadSizeInventoryReader.Header header) {}

                        public void entry(long ordinal, DownloadSizeInventoryReader.Entry entry) {
                          batch.add(
                              0,
                              entry.encodedKey().hex(),
                              OptionalLong.of(entry.encodedBytes()),
                              "keyBytes=" + entry.encodedKey().byteLength());
                        }

                        public void tag(long ordinal, DownloadSizeInventoryReader.Tag tag) {}
                      },
                      token::isCancellationRequested);
            }
            default ->
                throw new IOException("Unsupported required inventory role " + source.role());
          }
          var completed = decoded.completionProof();
          if (!completed.encodedProof().physicalSha256().equals(proof.object().digest().value()))
            throw new IOException("Inventory physical source changed");
          batch.flush();
          staging.complete(id, source.role().name(), batch.count, token);
        } catch (io.vibeminer.format.wow.root.StreamingRootException
            | io.vibeminer.protocol.tact.install.InstallManifestException
            | io.vibeminer.protocol.tact.download.DownloadInventoryException
            | io.vibeminer.protocol.tact.downloadsize.DownloadSizeInventoryException failed) {
          throw new IOException("Unsupported or invalid inventory source", failed);
        }
      }
      for (var source : sources) {
        long after = -1;
        for (; ; ) {
          token.throwIfCancellationRequested();
          var rows = staging.page(id, source.role(), after, limits.batchRows(), token);
          if (rows.isEmpty()) break;
          var forward = new HashMap<String, SqliteEncodingLookupIndex.Content>();
          if (source.role().equals("ROOT") || source.role().equals("INSTALL")) {
            var keys = rows.stream().map(row -> row.reference().key()).distinct().toList();
            for (int first = 0; first < keys.size(); first += encodingLimits.maxQueryKeys()) {
              var slice =
                  keys.subList(first, Math.min(keys.size(), first + encodingLimits.maxQueryKeys()));
              for (var content : en.lookup(slice, token))
                forward.put(content.contentKey(), content);
            }
          }
          var reverse = new HashMap<String, List<SqliteEncodingLookupIndex.EncodingMatch>>();
          if (source.role().equals("DOWNLOAD") || source.role().equals("SIZE")) {
            var keys = rows.stream().map(row -> row.reference().key()).distinct().toList();
            for (int first = 0; first < keys.size(); first += encodingLimits.maxQueryKeys()) {
              var slice =
                  keys.subList(first, Math.min(keys.size(), first + encodingLimits.maxQueryKeys()));
              var queries =
                  slice.stream()
                      .map(
                          k ->
                              k.length() == 18
                                  ? SqliteEncodingLookupIndex.ReverseQuery.prefix9(k)
                                  : SqliteEncodingLookupIndex.ReverseQuery.exact(k))
                      .toList();
              var results = en.reverseBatch(queries, token);
              for (int i = 0; i < slice.size(); i++)
                reverse.put(slice.get(i), results.get(i).matches());
            }
          }
          var resolvedRows = new ArrayList<InventoryStaging.Resolved>();
          for (var row : rows) {
            var ref = row.reference();
            Resolution resolution;
            if (source.role().equals("ROOT") || source.role().equals("INSTALL")) {
              var content = forward.get(ref.key());
              if (content == null)
                throw new IllegalStateException("EN forward result omitted requested key");
              var data =
                  content.decodedBytes().isEmpty() ? List.<Content>of() : List.of(convert(content));
              var actual =
                  canonical
                      .lookup(new ContentSlotKey("wow", ref.key()), token)
                      .map(c -> c.actualEncodingKey());
              resolution =
                  resolver.content(ref.key(), ref.declaredBytes(), new Facts(true, data), actual);
            } else {
              var matches = reverse.get(ref.key());
              if (matches.size() > 1) resolution = failure(Outcome.AMBIGUOUS_MAPPING);
              else if (matches.isEmpty() || matches.getFirst().origins().isEmpty())
                resolution = failure(Outcome.MISSING_MAPPING);
              else {
                var match = matches.getFirst();
                var contents = new ArrayList<Content>();
                for (var origin : match.origins())
                  contents.add(
                      new Content(
                          origin.contentKey(),
                          OptionalLong.of(origin.decodedBytes()),
                          List.of(new Candidate(match.encodingKey(), match.encodedBytes()))));
                var keys = contents.stream().map(Content::contentKey).distinct().toList();
                var actual =
                    keys.size() == 1
                        ? canonical
                            .lookup(new ContentSlotKey("wow", keys.getFirst()), token)
                            .map(c -> c.actualEncodingKey())
                        : Optional.<String>empty();
                resolution =
                    resolver.encoded(
                        ref.key(),
                        ref.declaredBytes().orElseThrow(),
                        new Facts(true, contents),
                        actual);
              }
            }
            resolvedRows.add(new InventoryStaging.Resolved(ref, resolution));
            after = ref.ordinal();
          }
          staging.resolveBatch(id, resolvedRows, token);
        }
      }
      long members =
          staging.seal(
              id,
              c -> {
                admissions.validateInTransaction(c, facts, token);
                InventoryStaging.requireContext(
                    c,
                    id,
                    reference.snapshotId(),
                    BuildMetadataAdmissionRepository.closureId(facts),
                    en.artifactId(),
                    en.generation(),
                    REVISION,
                    sources);
                en.validateInTransaction(c, token);
                requireCanonicalCompatibility(
                    c,
                    reference.snapshotId(),
                    BuildMetadataAdmissionRepository.closureId(facts),
                    en.artifactId(),
                    en.generation());
              },
              token);
      return new Prepared(id, members, REVISION);
    } catch (IOException | RuntimeException failure) {
      if (ownGeneration != null)
        try {
          staging.discard(ownGeneration);
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
      throw failure;
    } finally {
      for (var lease : leases) lease.close();
    }
  }

  private static void requireCanonicalCompatibility(
      java.sql.Connection c,
      String snapshot,
      String admission,
      String enArtifact,
      String enGeneration)
      throws java.sql.SQLException {
    // Conflict guard only: validated canonical.lookup supplies facts; SQL grants no proof
    // authority.
    try (var q =
        c.prepareStatement(
            "SELECT 1 FROM build_inventory_references r JOIN build_inventory_generations g ON"
                + " g.id=r.generation JOIN canonical_content_slots s ON s.namespace='wow' AND"
                + " s.content_key=r.ckey WHERE g.snapshot_id=? AND g.admission_id=? AND"
                + " g.en_artifact=? AND g.en_generation=? AND g.scope=? AND (s.encoding_key<>r.ekey"
                + " OR s.encoded_bytes<>r.encoded_bytes OR s.status NOT IN"
                + " ('PENDING','PUBLISHING','RECOVERY','RETAINED') OR"
                + " typeof(s.encoded_bytes)<>'integer' OR length(s.encoding_key)<>32) LIMIT 1")) {
      q.setString(1, snapshot);
      q.setString(2, admission);
      q.setString(3, enArtifact);
      q.setString(4, enGeneration);
      q.setString(5, REVISION);
      try (var rows = q.executeQuery()) {
        if (rows.next())
          throw new IllegalStateException(
              "Canonical representation changed during inventory preparation");
      }
    }
  }

  private static Content convert(SqliteEncodingLookupIndex.Content content) {
    return new Content(
        content.contentKey(),
        content.decodedBytes(),
        content.candidates().stream()
            .map(c -> new Candidate(c.encodingKey(), c.encodedBytes()))
            .toList());
  }

  private static Resolution failure(Outcome outcome) {
    return new Resolution(
        outcome, Optional.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
  }

  private final class Batch {
    final String id, role;
    final Limits limits;
    final CancellationToken token;
    final List<InventoryStaging.Reference> rows = new ArrayList<>();
    long count;

    Batch(String id, String role, Limits limits, CancellationToken token) {
      this.id = id;
      this.role = role;
      this.limits = limits;
      this.token = token;
    }

    void add(long block, String key, OptionalLong bytes, String raw) {
      token.throwIfCancellationRequested();
      rows.add(new InventoryStaging.Reference(role, count++, block, key, bytes, raw));
      if (rows.size() == limits.batchRows()) flush();
    }

    void flush() {
      if (!rows.isEmpty()) {
        staging.append(id, rows, limits.staging(), token);
        rows.clear();
      }
    }
  }
}
