package io.vibeminer.infrastructure.lookup;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import io.vibeminer.infrastructure.workspace.SqliteWorkspaceRepository;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;

/**
 * Real lookup persistence/protocol stack with generated fixtures, through its existing package
 * seam.
 */
public final class FileLookupFixtureBackend implements AutoCloseable {
  private final SyntheticLookupFixture fixture;
  private final FileObjectStore store;
  private final Path contentRoot;
  private final ExecutorService jobs = Executors.newFixedThreadPool(4),
      acquisitions = Executors.newFixedThreadPool(2),
      workspaceJobs = Executors.newSingleThreadExecutor();
  private final OfficialFileLookupGateway gateway;
  private final DefaultFileLookupService delegate;
  private final WorkspaceService workspaces;
  private final List<BuildSnapshot> builds;
  public final AtomicInteger plans = new AtomicInteger(),
      retentions = new AtomicInteger(),
      lookups = new AtomicInteger();
  private volatile Set<Long> lastIds = Set.of();
  private final FileLookupService service =
      new FileLookupService() {
        public CompletionStage<RootPlan> planRoot(BuildSnapshot build, LookupBudgets budgets) {
          plans.incrementAndGet();
          return delegate.planRoot(build, budgets);
        }

        public CompletionStage<VerifiedSourceAlias> retainRoot(RootSelectionPlan plan) {
          retentions.incrementAndGet();
          return delegate.retainRoot(plan);
        }

        public CompletionStage<RootLookupResult> lookupOffline(
            BuildSnapshot build, Set<Long> ids, LookupBudgets budgets) {
          lookups.incrementAndGet();
          lastIds = Set.copyOf(ids);
          return delegate.lookupOffline(build, ids, budgets);
        }

        public void close() {
          delegate.close();
        }
      };

  public FileLookupFixtureBackend(Path root, boolean offline) throws Exception {
    fixture = new SyntheticLookupFixture(false, false, true, true);
    fixture.rejectNetwork = offline;
    builds =
        List.of(
            fixture.build("us.cdn.blizzard.com", "DEMO-ROOT-A"),
            fixture.build("us.cdn.blizzard.com", "DEMO-ROOT-B"));
    contentRoot = root.resolve("content");
    store = new FileObjectStore(contentRoot);
    var database = new SqliteCatalog(root.resolve("catalog.sqlite"));
    var repository = new SqliteWorkspaceRepository(database);
    if (!offline) {
      repository.save(
          Workspace.create(
              builds.get(0), "enUS", "Synthetic root investigation A", SyntheticLookupFixture.NOW));
      repository.save(
          Workspace.create(
              builds.get(1), "deDE", "Synthetic root investigation B", SyntheticLookupFixture.NOW));
    }
    var clock = Clock.fixed(SyntheticLookupFixture.NOW, ZoneOffset.UTC);
    workspaces = new DefaultWorkspaceService(repository, workspaceJobs, clock);
    gateway = new OfficialFileLookupGateway(store, fixture, clock);
    delegate =
        new DefaultFileLookupService(
            gateway, new SqliteBuildObjectCatalog(database, store), store, jobs, acquisitions);
  }

  public List<BuildSnapshot> builds() {
    return builds;
  }

  public WorkspaceService workspaces() {
    return workspaces;
  }

  public FileLookupService service() {
    return service;
  }

  public int transportCalls() {
    return fixture.transportCalls.get();
  }

  public int rootRequests() {
    return (int)
        fixture.requests.stream().filter(r -> r.encodedKey().equals(fixture.rootEKey)).count();
  }

  public Set<Long> lastIds() {
    return lastIds;
  }

  public String rootKey() {
    return fixture.rootEKey;
  }

  public int encodedBytes() {
    return fixture.encodedRoot.length;
  }

  public int decodedBytes() {
    return fixture.root.length;
  }

  public void holdRoot() {
    fixture.blockedRequest = null;
    fixture.blockedRoot = new CompletableFuture<>();
  }

  public boolean wireCancelled() {
    return fixture.blockedRoot != null && fixture.blockedRoot.isCancelled();
  }

  public boolean wireStarted() {
    return fixture.blockedRequest != null;
  }

  public void releaseRoot() {
    fixture.blockedRoot = null;
    fixture.blockedRequest = null;
  }

  public void corruptRoot() throws Exception {
    String digest = SyntheticLookupFixture.hash("SHA-256", fixture.encodedRoot);
    byte[] bytes = fixture.encodedRoot.clone();
    bytes[bytes.length - 1] ^= 1;
    Files.write(
        contentRoot.resolve("objects").resolve(digest.substring(0, 2)).resolve(digest.substring(2)),
        bytes);
  }

  @Override
  public void close() throws Exception {
    delegate.close();
    gateway.close();
    jobs.shutdownNow();
    acquisitions.shutdownNow();
    workspaceJobs.shutdownNow();
    jobs.awaitTermination(3, TimeUnit.SECONDS);
    acquisitions.awaitTermination(3, TimeUnit.SECONDS);
    workspaceJobs.awaitTermination(3, TimeUnit.SECONDS);
    store.close();
  }
}

/** Independent synthetic EN/BLTE/TSFM fixture; no real Blizzard bytes or external fixtures. */
final class SyntheticLookupFixture implements LookupTransport {
  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  final Map<String, byte[]> objects = new HashMap<>();
  final byte[] config, root, encodedRoot;
  final String rootCKey, rootEKey, encodingEKey;
  String secondRootEKey;
  final List<CdnRangeRequest> requests = Collections.synchronizedList(new ArrayList<>());
  final AtomicInteger transportCalls = new AtomicInteger();
  final AtomicInteger configurationRequests = new AtomicInteger();
  volatile boolean rejectNetwork, wrongRangeIdentity, corruptEncodingPage, corruptRootChunk;
  volatile CompletableFuture<AcquiredRange> blockedRoot;
  volatile CdnRangeRequest blockedRequest;

  SyntheticLookupFixture() throws Exception {
    this(false, false);
  }

  SyntheticLookupFixture(boolean wrongRootContentKey, boolean malformedRoot) throws Exception {
    this(wrongRootContentKey, malformedRoot, false, false);
  }

  SyntheticLookupFixture(
      boolean wrongRootContentKey,
      boolean malformedRoot,
      boolean multipleCandidates,
      boolean missingMetadata)
      throws Exception {
    this(wrongRootContentKey, malformedRoot, multipleCandidates, missingMetadata, false);
  }

  SyntheticLookupFixture(
      boolean wrongRootContentKey,
      boolean malformedRoot,
      boolean multipleCandidates,
      boolean missingMetadata,
      boolean largeSpecificationSection)
      throws Exception {
    byte[] decoded = root();
    if (malformedRoot) ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 12345);
    root = decoded;
    String actual = hash("MD5", root);
    rootCKey =
        wrongRootContentKey ? (actual.charAt(0) == 'a' ? "b" : "a") + actual.substring(1) : actual;
    encodedRoot = blte(Arrays.copyOfRange(root, 0, 40), Arrays.copyOfRange(root, 40, root.length));
    rootEKey = ekey(encodedRoot);
    objects.put(rootEKey, encodedRoot);
    if (multipleCandidates) {
      byte[] second =
          blte(Arrays.copyOfRange(root, 0, 50), Arrays.copyOfRange(root, 50, root.length));
      secondRootEKey = ekey(second);
      objects.put(secondRootEKey, second);
    }
    byte[] cp = new byte[1024], ep = new byte[1024];
    var content = ByteBuffer.wrap(cp).order(ByteOrder.BIG_ENDIAN);
    content.put((byte) (multipleCandidates ? 2 : 1));
    u40(content, root.length);
    content.put(HexFormat.of().parseHex(rootCKey)).put(HexFormat.of().parseHex(rootEKey));
    if (multipleCandidates) content.put(HexFormat.of().parseHex(secondRootEKey));
    var encoding = ByteBuffer.wrap(ep).order(ByteOrder.BIG_ENDIAN);
    var encodedKeys = new ArrayList<String>();
    encodedKeys.add(rootEKey);
    if (multipleCandidates && !missingMetadata) encodedKeys.add(secondRootEKey);
    encodedKeys.sort(String::compareTo);
    for (String key : encodedKeys) {
      encoding.put(HexFormat.of().parseHex(key)).putInt(key.equals(rootEKey) ? 0 : 1);
      u40(encoding, objects.get(key).length);
    }
    byte[] ci = concat(HexFormat.of().parseHex(rootCKey), md5(cp));
    byte[] ei = concat(HexFormat.of().parseHex(encodedKeys.getFirst()), md5(ep));
    var header = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
    byte[] specification = largeSpecificationSection ? new byte[16 << 20] : new byte[0];
    header
        .put(new byte[] {'E', 'N', 1, 16, 16})
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(1)
        .putInt(1)
        .put((byte) 0)
        .putInt(specification.length);
    byte[] enDecoded = concat(header.array(), specification, ci, cp, ei, ep);
    byte[] enEncoded =
        largeSpecificationSection
            ? blte(header.array(), specification, ci, cp, ei, ep)
            : blte(header.array(), ci, cp, ei, ep);
    encodingEKey = ekey(enEncoded);
    objects.put(encodingEKey, enEncoded);
    config =
        ("root = "
                + rootCKey
                + "\nencoding = "
                + hash("MD5", enDecoded)
                + " "
                + encodingEKey
                + "\nencoding-size = "
                + enDecoded.length
                + " "
                + enEncoded.length
                + "\n")
            .getBytes(StandardCharsets.UTF_8);
  }

  BuildSnapshot build(String host, String version) throws Exception {
    String buildKey = hash("MD5", config), cdnKey = "d".repeat(32);
    String versions =
        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
            + buildKey
            + "|"
            + cdnKey
            + "|"
            + version
            + "\n";
    String cdns =
        "Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n"
            + "us|tpr/wow|"
            + host
            + "|https://"
            + host
            + "/|tpr/configs/data\n";
    return new BuildSnapshot(
        "wow",
        "Synthetic",
        "Published",
        "us",
        version,
        buildKey,
        cdnKey,
        NOW,
        true,
        new BuildEvidence(
            "",
            "",
            List.of(
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/versions",
                    NOW,
                    versions.getBytes(StandardCharsets.UTF_8)),
                new SourceDocument(
                    "https://us.version.battle.net/v2/products/wow/cdns",
                    NOW,
                    cdns.getBytes(StandardCharsets.UTF_8)))));
  }

  public CompletableFuture<AcquiredBytes> configuration(CdnRequest request) {
    transportCalls.incrementAndGet();
    if (rejectNetwork) throw new AssertionError("Offline attempted configuration HTTP");
    configurationRequests.incrementAndGet();
    try {
      return CompletableFuture.completedFuture(
          new AcquiredBytes(request, NOW, config, hash("SHA-256", config), true));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  public CompletableFuture<AcquiredRange> range(CdnRangeRequest request) {
    transportCalls.incrementAndGet();
    if (rejectNetwork) throw new AssertionError("Offline attempted range HTTP");
    requests.add(request);
    try {
      if (blockedRoot != null
          && blockedRequest == null
          && request.encodedKey().equals(rootEKey)
          && request.start() >= 60) {
        blockedRequest = request;
        return blockedRoot;
      }
      byte[] source =
          Objects.requireNonNull(objects.get(request.encodedKey()), "Unexpected object");
      if (request.wholeEncodedLength() != source.length)
        throw new AssertionError("Wrong whole length");
      byte[] bytes =
          Arrays.copyOfRange(
              source,
              Math.toIntExact(request.start()),
              Math.toIntExact(request.start() + request.count()));
      if (corruptRootChunk && request.encodedKey().equals(rootEKey) && request.start() >= 60)
        bytes[bytes.length - 1] ^= 1;
      if (corruptEncodingPage
          && request.encodedKey().equals(encodingEKey)
          && request.count() == 1024) bytes[bytes.length - 1] ^= 1;
      var actual =
          wrongRangeIdentity
              ? new CdnRangeRequest(
                  request.source(),
                  "f".repeat(32),
                  request.wholeEncodedLength(),
                  request.start(),
                  request.count(),
                  request.maximumBytes())
              : request;
      return CompletableFuture.completedFuture(
          new AcquiredRange(actual, actual.uri(), NOW, bytes, hash("SHA-256", bytes)));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  static String hash(String algorithm, byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value));
  }

  static byte[] md5(byte[] value) throws Exception {
    return MessageDigest.getInstance("MD5").digest(value);
  }

  static String ekey(byte[] encoded) throws Exception {
    return hash("MD5", Arrays.copyOf(encoded, ByteBuffer.wrap(encoded).getInt(4)));
  }

  static byte[] concat(byte[]... parts) throws IOException {
    var out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part);
    return out.toByteArray();
  }

  static void u40(ByteBuffer out, long value) {
    out.put((byte) (value >>> 32)).putInt((int) value);
  }

  static byte[] blte(byte[]... decoded) throws Exception {
    var header = new ByteArrayOutputStream();
    var out = new DataOutputStream(header);
    out.writeBytes("BLTE");
    out.writeInt(12 + 24 * decoded.length);
    out.writeInt(0x0f000000 | decoded.length);
    var body = new ByteArrayOutputStream();
    for (int i = 0; i < decoded.length; i++) {
      byte[] encoded;
      if (i % 2 == 0) encoded = concat(new byte[] {'N'}, decoded[i]);
      else {
        var zipped = new ByteArrayOutputStream();
        zipped.write('Z');
        try (var zip = new DeflaterOutputStream(zipped)) {
          zip.write(decoded[i]);
        }
        encoded = zipped.toByteArray();
      }
      out.writeInt(encoded.length);
      out.writeInt(decoded[i].length);
      out.write(md5(encoded));
      body.write(encoded);
    }
    return concat(header.toByteArray(), body.toByteArray());
  }

  static byte[] root() {
    var out = ByteBuffer.allocate(24 + 17 + 3 * 28 + 17 + 2 * 20).order(ByteOrder.LITTLE_ENDIAN);
    out.put(new byte[] {'T', 'S', 'F', 'M'}).putInt(24).putInt(2).putInt(5).putInt(3).putInt(0);
    out.putInt(3).putInt(2).putInt(0x20000000).putInt(4).put((byte) 0);
    out.putInt(7).putInt(34).putInt(56);
    for (int i = 0; i < 3; i++)
      out.put(HexFormat.of().parseHex(Integer.toHexString(i + 1).repeat(32)));
    out.putLong(-1L).putLong(0).putLong(0x123);
    out.putInt(2).putInt(0x10).putInt(0x10000000).putInt(8).put((byte) 0x80);
    out.putInt(42).putInt(80);
    out.put(HexFormat.of().parseHex("4".repeat(32))).put(HexFormat.of().parseHex("5".repeat(32)));
    return out.array();
  }
}
