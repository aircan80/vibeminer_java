package io.vibeminer.infrastructure.encoding;

import io.vibeminer.application.*;
import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildopening.BuildMetadataAdmission.Role;
import io.vibeminer.infrastructure.buildopening.*;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.protocol.tact.blte.StreamingDecodedBlteInputStream;
import io.vibeminer.protocol.tact.encoding.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/**
 * Local retained EN interpretation; neither this index nor a DTO authenticates a build association.
 */
public final class SqliteEncodingLookupIndex {
  public record Limits(
      long maxRows,
      long maxCandidates,
      long maxLogicalDerivedBytes,
      int batchRows,
      long maxBatches,
      int maxQueryKeys,
      int maxQueryCandidates,
      ReverseLimits reverse) {
    public Limits(
        long rows,
        long candidates,
        long bytes,
        int batch,
        long batches,
        int keys,
        int queryCandidates) {
      this(
          rows,
          candidates,
          bytes,
          batch,
          batches,
          keys,
          queryCandidates,
          new ReverseLimits(256, 65536, 256, 4096, 65536, 10000000));
    }

    public Limits {
      Objects.requireNonNull(reverse);
      new EncodingLookupStaging.Limits(
          maxRows,
          maxCandidates,
          maxLogicalDerivedBytes,
          batchRows,
          maxBatches,
          maxQueryKeys,
          maxQueryCandidates);
    }

    EncodingLookupStaging.Limits internal() {
      return new EncodingLookupStaging.Limits(
          maxRows,
          maxCandidates,
          maxLogicalDerivedBytes,
          batchRows,
          maxBatches,
          maxQueryKeys,
          maxQueryCandidates);
    }
  }

  /**
   * EN query work is conservatively charged in 100-instruction units plus one unit per SQL
   * statement. Exact-admission authority checks have their own bounded metadata closure and are
   * outside this query-work ceiling. These limits do not assert a whole-operation wall-time bound.
   */
  public record ReverseLimits(
      int maxMatchesPerReference,
      int maxOriginsPerReference,
      int maxContentsPerReference,
      int maxBatchMatches,
      int maxBatchOrigins,
      long maxSqlInstructions) {
    public ReverseLimits {
      if (maxMatchesPerReference < 1
          || maxMatchesPerReference > 65536
          || maxOriginsPerReference < 1
          || maxContentsPerReference < 1
          || maxBatchMatches < 1
          || maxBatchOrigins < 1
          || maxSqlInstructions < 1000)
        throw new IllegalArgumentException("Invalid reverse EN bounds");
    }
  }

  public record ReverseQuery(String key, int keyBytes) {
    public ReverseQuery {
      if ((keyBytes != 9 && keyBytes != 16)
          || key == null
          || !key.matches("[0-9a-f]{" + (keyBytes * 2) + "}"))
        throw new IllegalArgumentException("Exact full EKey or nine-byte prefix required");
    }

    public static ReverseQuery exact(String key) {
      return new ReverseQuery(key, 16);
    }

    public static ReverseQuery prefix9(String key) {
      return new ReverseQuery(key, 9);
    }
  }

  public record Origin(String contentKey, long decodedBytes, int candidateOrdinal) {}

  public record EncodingMatch(
      String encodingKey,
      OptionalLong encodedBytes,
      OptionalLong specificationOrdinal,
      List<Origin> origins) {
    public EncodingMatch {
      origins = List.copyOf(origins);
    }
  }

  public record ReverseResult(ReverseQuery query, List<EncodingMatch> matches) {
    public ReverseResult {
      matches = List.copyOf(matches);
    }
  }

  public record Candidate(
      String encodingKey, OptionalLong encodedBytes, OptionalLong specificationOrdinal) {}

  /** Empty decodedBytes means absent from this complete EN, not absent from the CDN. */
  public record Content(String contentKey, OptionalLong decodedBytes, List<Candidate> candidates) {
    public Content {
      candidates = List.copyOf(candidates);
    }
  }

  private final ObjectStore store;
  private final BuildMetadataAdmissionRepository admissions;
  private final EncodingLookupStaging index;
  private final String owner;

  public SqliteEncodingLookupIndex(
      SqliteCatalog catalog,
      ObjectStore store,
      BuildMetadataAdmissionRepository admissions,
      UUID ownerEpoch) {
    this.store = Objects.requireNonNull(store);
    this.admissions = Objects.requireNonNull(admissions);
    this.index = new EncodingLookupStaging(catalog);
    this.owner = Objects.requireNonNull(ownerEpoch).toString();
  }

  /** Verifies admission once, then the complete local EN once; holds its CAS lease until close. */
  public Session open(
      BuildLibraryReference reference,
      String locale,
      String scopeRevision,
      MetadataStreamBudgets budgets,
      Limits limits,
      CancellationToken token)
      throws IOException {
    Objects.requireNonNull(reference);
    Objects.requireNonNull(budgets);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(token);
    token.throwIfCancellationRequested();
    var facts =
        admissions
            .find(reference, locale, scopeRevision, token)
            .orElseThrow(
                () ->
                    new IllegalStateException("Complete exact-build metadata admission required"));
    var en =
        facts.metadata().stream()
            .filter(f -> f.role() == Role.ENCODING)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Admitted encoding role missing"));
    var proof = en.proof();
    long decodedBytes = proof.decodedAttestation().orElseThrow().actualDecodedBytes();
    if (proof.object().length() > budgets.blte().maximumEncodedBytes()
        || decodedBytes > budgets.blte().maximumDecodedBytes()
        || decodedBytes > budgets.encoding().maxDecodedBytes()
        || decodedBytes > budgets.maximumDecodedWorkBytes()
        || MetadataStreamBudgets.requiredGrammarWork(
                io.vibeminer.domain.content.MetadataStructureAttestation.Kind.ENCODING,
                decodedBytes)
            > budgets.maximumGrammarWorkBytes())
      throw new IllegalArgumentException("EN index replay exceeds admitted work");
    byte[] record = CanonicalContentRecords.encode(proof);
    String artifact =
        hash(
            String.join(
                    "\n",
                    "encoding-lookup-v1",
                    en.slot().namespace(),
                    proof.contentKey(),
                    proof.actualEncodingKey(),
                    proof.object().digest().value(),
                    Long.toString(proof.object().length()),
                    Long.toString(decodedBytes),
                    "encoding-v1-full-r1")
                .getBytes(StandardCharsets.US_ASCII));
    var lease = store.openRead(proof.object().digest(), token);
    boolean handedOff = false;
    try {
      if (!lease.object().equals(proof.object()))
        throw new IllegalStateException("Retained EN physical geometry changed");
      var retained = index.findReady(artifact, record, token);
      EncodingLookupStaging.Generation generation;
      try (var decoded =
          new StreamingDecodedBlteInputStream(
              lease.stream(),
              proof.actualEncodingKey(),
              proof.contentKey(),
              proof.object().length(),
              decodedBytes,
              budgets.blte(),
              token::isCancellationRequested)) {
        Runnable complete =
            () -> {
              try {
                var completed = decoded.completionProof();
                if (!completed
                        .encodedProof()
                        .physicalSha256()
                        .equals(proof.object().digest().value())
                    || completed.encodedProof().encodedBytes() != proof.object().length())
                  throw new IllegalStateException("EN whole encoded source changed");
                admissions.validateCurrentAuthority(facts, token);
              } catch (IOException failed) {
                throw new UncheckedIOException(failed);
              }
            };
        if (retained.isPresent()) {
          try {
            new FullEncodingInventory()
                .read(decoded, budgets.encoding(), DISCARD, token::isCancellationRequested);
          } catch (FullEncodingInventoryException failed) {
            throw new IOException("Invalid complete EN grammar", failed);
          }
          complete.run();
          generation = retained.get();
          index.requireSource(generation, record, token);
        } else
          generation =
              index.build(
                  artifact,
                  record,
                  owner,
                  decoded,
                  budgets.encoding(),
                  limits.internal(),
                  complete,
                  token);
      } catch (UncheckedIOException failed) {
        throw failed.getCause();
      }
      token.throwIfCancellationRequested();
      var session = new Session(facts, generation, record, limits, lease);
      handedOff = true;
      return session;
    } finally {
      if (!handedOff) lease.close();
    }
  }

  public final class Session implements AutoCloseable {
    private final BuildMetadataAdmissionFacts facts;
    private final EncodingLookupStaging.Generation identity;
    private final byte[] sourceProof;
    private final Limits limits;
    private final ObjectReadLease lease;
    private boolean closed;

    private Session(
        BuildMetadataAdmissionFacts facts,
        EncodingLookupStaging.Generation identity,
        byte[] sourceProof,
        Limits limits,
        ObjectReadLease lease) {
      this.facts = facts;
      this.identity = identity;
      this.sourceProof = sourceProof.clone();
      this.limits = limits;
      this.lease = lease;
    }

    public synchronized List<Content> lookup(List<String> contentKeys, CancellationToken token) {
      if (closed) throw new IllegalStateException("EN lookup session is closed");
      Objects.requireNonNull(token);
      contentKeys = List.copyOf(contentKeys);
      if (contentKeys.size() > limits.maxQueryKeys())
        throw new IllegalArgumentException("EN query key bound exceeded");
      for (var key : contentKeys)
        if (!key.matches("[0-9a-f]{32}"))
          throw new IllegalArgumentException("Expected canonical CKey");
      token.throwIfCancellationRequested();
      admissions.validateCurrentAuthority(facts, token);
      index.requireSource(identity, sourceProof, token);
      var result =
          index.lookup(identity, contentKeys, limits.internal(), token).stream()
              .map(
                  c ->
                      new Content(
                          c.contentKey(),
                          c.decodedBytes(),
                          c.candidates().stream()
                              .map(
                                  e ->
                                      new Candidate(
                                          e.encodingKey(),
                                          e.encodedBytes(),
                                          e.specificationOrdinal()))
                              .toList()))
              .toList();
      token.throwIfCancellationRequested();
      admissions.validateCurrentAuthority(facts, token);
      index.requireSource(identity, sourceProof, token);
      return result;
    }

    public synchronized List<ReverseResult> reverseBatch(
        List<ReverseQuery> queries, CancellationToken token) {
      if (closed) throw new IllegalStateException("EN lookup session is closed");
      Objects.requireNonNull(token);
      queries = List.copyOf(queries);
      if (queries.size() > limits.maxQueryKeys())
        throw new EncodingLookupException(
            EncodingLookupException.Code.LIMIT, "Reverse reference batch bound exceeded");
      token.throwIfCancellationRequested();
      return index.reverseBatch(
          identity, queries, limits.reverse(), c -> validateInTransaction(c, token), token);
    }

    public List<EncodingMatch> reverseExact(String key, CancellationToken token) {
      return reverseBatch(List.of(ReverseQuery.exact(key)), token).getFirst().matches();
    }

    public List<EncodingMatch> reversePrefix9(String prefix, CancellationToken token) {
      return reverseBatch(List.of(ReverseQuery.prefix9(prefix)), token).getFirst().matches();
    }

    /** Caller owns transaction and must keep this Session open through its final commit. */
    public synchronized void validateInTransaction(Connection connection, CancellationToken token) {
      if (closed) throw new IllegalStateException("EN lookup session is closed");
      Objects.requireNonNull(connection);
      Objects.requireNonNull(token);
      token.throwIfCancellationRequested();
      admissions.validateInTransaction(connection, facts, token);
      index.requireSourceInTransaction(connection, identity, sourceProof, token);
    }

    public String artifactId() {
      return identity.artifact();
    }

    public String generation() {
      return identity.generation();
    }

    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        lease.close();
      }
    }
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static final FullEncodingInventory.Consumer DISCARD =
      new FullEncodingInventory.Consumer() {
        public void header(IndexedEncodingParser.Layout layout) {}

        public void specification(int ordinal, String value) {}

        public void content(int page, long ordinal, EncodingManifest.ContentEntry value) {}

        public void encoding(int page, long ordinal, EncodingManifest.EncodedEntry value) {}

        public void trailer(String value) {}
      };
}
