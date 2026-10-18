import io.vibeminer.infrastructure.acquisition.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Offline deterministic proof-composition tests. All bytes below are generated, synthetic fixtures.
 */
public final class ItemCohortProbeSelfTest {
  static int checks;

  static void check(boolean value, String reason) {
    checks++;
    if (!value) throw new AssertionError(reason);
  }

  interface Checked {
    void run() throws Exception;
  }

  static Throwable failure(Checked work) throws Exception {
    try {
      work.run();
      throw new AssertionError("Expected refusal");
    } catch (AssertionError assertion) {
      throw assertion;
    } catch (Exception expected) {
      return expected;
    }
  }

  static String hash(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }

  static byte[] md5(byte[] bytes) throws Exception {
    return MessageDigest.getInstance("MD5").digest(bytes);
  }

  static byte[] concat(byte[]... parts) throws Exception {
    var result = new ByteArrayOutputStream();
    for (byte[] bytes : parts) result.write(bytes);
    return result.toByteArray();
  }

  static byte[] blte(byte[]... decoded) throws Exception {
    var header = ByteBuffer.allocate(12 + 24 * decoded.length).order(ByteOrder.BIG_ENDIAN);
    header
        .put(new byte[] {'B', 'L', 'T', 'E'})
        .putInt(header.capacity())
        .put((byte) 15)
        .put((byte) (decoded.length >> 16))
        .put((byte) (decoded.length >> 8))
        .put((byte) decoded.length);
    var body = new ByteArrayOutputStream();
    for (byte[] data : decoded) {
      byte[] encoded = concat(new byte[] {'N'}, data);
      header.putInt(encoded.length).putInt(data.length).put(md5(encoded));
      body.write(encoded);
    }
    return concat(header.array(), body.toByteArray());
  }

  static String ekey(byte[] bytes) throws Exception {
    return hash("MD5", Arrays.copyOf(bytes, ByteBuffer.wrap(bytes).getInt(4)));
  }

  static void u40(ByteBuffer b, long value) {
    for (int shift = 32; shift >= 0; shift -= 8) b.put((byte) (value >> shift));
  }

  static byte[] table(long table, long layout, int flags) {
    var bytes = ByteBuffer.allocate(264).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put(new byte[] {'W', 'D', 'C', '5'}).putInt(1);
    bytes.position(136);
    bytes
        .putInt(3)
        .putInt(2)
        .putInt(8)
        .putInt(0)
        .putInt((int) table)
        .putInt((int) layout)
        .putInt(1)
        .putInt(3)
        .putInt(0)
        .putShort((short) flags)
        .putShort((short) 0)
        .putInt(2)
        .putInt(0)
        .putInt(0)
        .putInt(0)
        .putInt(0)
        .putInt(0)
        .putInt(1);
    return bytes.array();
  }

  static byte[] root(String item, String sparse) throws Exception {
    var bytes = ByteBuffer.allocate(24 + 2 * (17 + 2 * 20)).order(ByteOrder.LITTLE_ENDIAN);
    bytes.put(new byte[] {'T', 'S', 'F', 'M'}).putInt(24).putInt(2).putInt(4).putInt(0).putInt(0);
    for (int locale : new int[] {2, 4}) {
      bytes.putInt(2).putInt(locale).putInt(0x10000000).putInt(0).put((byte) 0);
      bytes.putInt(841626).putInt(1572924 - 841626 - 1);
      bytes.put(HexFormat.of().parseHex(item)).put(HexFormat.of().parseHex(sparse));
    }
    return bytes.array();
  }

  static final class Fixture implements ItemCohortProbe.Source {
    final Map<String, byte[]> objects = new HashMap<>(), configs = new HashMap<>();
    final List<CdnRangeRequest> transfers = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    String build, cdn, encodingKey, rootKey, itemKey, sparseKey;
    boolean corruptPage, corruptIndex, wrongIdentity;

    Fixture(boolean wrongRootCKey) throws Exception {
      this(wrongRootCKey, false);
    }

    Fixture(boolean wrongRootCKey, boolean largeLateRootChunk) throws Exception {
      byte[] item = table(0x50238ec2L, 0x996192aaL, 0), sparse = table(0x919be54eL, 0x1c17d17fL, 1);
      String itemCKey = hash("MD5", item), sparseCKey = hash("MD5", sparse);
      byte[] root = root(itemCKey, sparseCKey);
      if (largeLateRootChunk) {
        var extra = ByteBuffer.allocate(17 + 16 * 20).order(ByteOrder.LITTLE_ENDIAN);
        extra.putInt(16).putInt(2).putInt(0x10000000).putInt(0).put((byte) 0);
        for (int i = 0; i < 16; i++) extra.putInt(0);
        extra.put(new byte[16 * 16]);
        root = concat(root, extra.array());
        ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 20);
      }
      String rootCKey = hash("MD5", root);
      if (wrongRootCKey) rootCKey = (rootCKey.charAt(0) == 'a' ? "b" : "a") + rootCKey.substring(1);
      byte[] rootBLTE =
          blte(Arrays.copyOfRange(root, 0, 40), Arrays.copyOfRange(root, 40, root.length));
      byte[] itemBLTE = blte(item), sparseBLTE = blte(sparse);
      rootKey = ekey(rootBLTE);
      itemKey = ekey(itemBLTE);
      sparseKey = ekey(sparseBLTE);
      objects.put(rootKey, rootBLTE);
      objects.put(itemKey, itemBLTE);
      objects.put(sparseKey, sparseBLTE);
      record Entry(String ckey, String ekey, int decoded, int encoded) {}
      var entries =
          new ArrayList<>(
              List.of(
                  new Entry(rootCKey, rootKey, root.length, rootBLTE.length),
                  new Entry(itemCKey, itemKey, item.length, itemBLTE.length),
                  new Entry(sparseCKey, sparseKey, sparse.length, sparseBLTE.length)));
      entries.sort(Comparator.comparing(Entry::ckey));
      var cp = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
      for (var entry : entries) {
        cp.put((byte) 1);
        u40(cp, entry.decoded);
        cp.put(HexFormat.of().parseHex(entry.ckey)).put(HexFormat.of().parseHex(entry.ekey));
      }
      byte[] ci = concat(HexFormat.of().parseHex(entries.getFirst().ckey), md5(cp.array()));
      entries.sort(Comparator.comparing(Entry::ekey));
      var ep = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
      for (var entry : entries) {
        ep.put(HexFormat.of().parseHex(entry.ekey)).putInt(0);
        u40(ep, entry.encoded);
      }
      byte[] ei = concat(HexFormat.of().parseHex(entries.getFirst().ekey), md5(ep.array()));
      var en = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN);
      en.put(new byte[] {'E', 'N', 1, 16, 16})
          .putShort((short) 1)
          .putShort((short) 1)
          .putInt(1)
          .putInt(1)
          .put((byte) 0)
          .putInt(0);
      byte[] encodingDecoded = concat(en.array(), ci, cp.array(), ei, ep.array());
      byte[] encoding = blte(en.array(), ci, cp.array(), ei, ep.array());
      encodingKey = ekey(encoding);
      objects.put(encodingKey, encoding);
      byte[] buildBytes =
          ("root = "
                  + rootCKey
                  + "\nencoding = "
                  + hash("MD5", encodingDecoded)
                  + " "
                  + encodingKey
                  + "\nencoding-size = "
                  + encodingDecoded.length
                  + " "
                  + encoding.length
                  + "\nbuild-name = synthetic-only\nbuild-uid = wow\n")
              .getBytes(StandardCharsets.UTF_8);
      byte[] cdnBytes = "archives =\n".getBytes(StandardCharsets.UTF_8);
      build = hash("MD5", buildBytes);
      cdn = hash("MD5", cdnBytes);
      configs.put(build, buildBytes);
      configs.put(cdn, cdnBytes);
    }

    public AcquiredBytes configuration(CdnRequest request) throws Exception {
      byte[] bytes = configs.get(request.key());
      return new AcquiredBytes(request, Instant.EPOCH, bytes, hash("SHA-256", bytes), true);
    }

    public AcquiredRange range(CdnRangeRequest request) throws Exception {
      transfers.add(request);
      byte[] object = objects.get(request.encodedKey());
      if (object == null) throw new AssertionError("Unknown synthetic object");
      check(request.wholeEncodedLength() == object.length, "Pinned whole length must agree");
      byte[] result =
          Arrays.copyOfRange(
              object, (int) request.start(), (int) (request.start() + request.count()));
      if (request.encodedKey().equals(encodingKey)
          && ((corruptPage && request.count() == 1024) || (corruptIndex && request.count() == 33)))
        result[result.length - 1] ^= 1;
      if (wrongIdentity) {
        var changed =
            new CdnRangeRequest(
                request.source(),
                "f".repeat(32),
                request.wholeEncodedLength(),
                request.start(),
                request.count(),
                request.maximumBytes());
        return new AcquiredRange(
            changed, changed.uri(), Instant.EPOCH, result, hash("SHA-256", result));
      }
      return new AcquiredRange(
          request, request.uri(), Instant.EPOCH, result, hash("SHA-256", result));
    }

    ItemCohortProbe probe(ItemCohortProbe.Limits limits) {
      return new ItemCohortProbe(
          this,
          ItemCohortProbe.OFFICIAL,
          new ItemCohortProbe.Budget(limits, () -> 0L, () -> false),
          events::add);
    }

    void run() throws Exception {
      probe(ItemCohortProbe.Limits.defaults()).run(build, cdn);
    }
  }

  static void happyPathRetainsVariantsWithoutWholeTableClaim() throws Exception {
    var fixture = new Fixture(false);
    fixture.run();
    check(
        fixture.events.stream().anyMatch(s -> s.startsWith("VERIFIED COMPLETE ROOT")),
        "Complete root CKey must be verified");
    check(
        fixture.events.stream().filter(s -> s.startsWith("HEADER OBSERVED")).count() == 4,
        "All four locale memberships retained");
    check(
        fixture.events.stream().filter(s -> s.startsWith("REUSE authenticated header")).count()
            == 2,
        "Identical header evidence reused without duplicate download");
    check(
        fixture.events.stream()
            .filter(s -> s.startsWith("HEADER OBSERVED"))
            .allMatch(
                s ->
                    s.contains("candidateTableLayoutMatch=true")
                        && s.contains("fullTableCKeyVerified=false")
                        && s.contains("rowsSupported=false")),
        "Only header identity supported");
    int rootProof = -1, firstMembership = -1;
    for (int i = 0; i < fixture.events.size(); i++) {
      if (fixture.events.get(i).startsWith("VERIFIED COMPLETE ROOT")) rootProof = i;
      if (firstMembership < 0 && fixture.events.get(i).startsWith("VERIFIED ROOT MEMBERSHIP"))
        firstMembership = i;
    }
    check(
        rootProof >= 0 && firstMembership > rootProof,
        "Memberships must follow complete CKey proof");
    check(
        fixture.transfers.stream()
            .filter(r -> r.encodedKey().equals(fixture.encodingKey))
            .noneMatch(r -> r.count() == r.wholeEncodedLength()),
        "Never fetch complete EN");
    check(
        fixture.events.stream().anyMatch(s -> s.contains("wholeENCKeyVerified=false")),
        "Selected EN proof scope explicit");
  }

  static void invalidRootCKeyWithholdsMembershipsAndTables() throws Exception {
    var fixture = new Fixture(true);
    var error = failure(fixture::run);
    check(
        error.getMessage().contains("CKey/length mismatch"),
        "Wrong root CKey must fail closed after full read");
    check(
        fixture.events.stream().noneMatch(s -> s.startsWith("VERIFIED ROOT MEMBERSHIP")),
        "No root memberships escape failure");
    check(
        fixture.transfers.stream()
            .noneMatch(
                r ->
                    r.encodedKey().equals(fixture.itemKey)
                        || r.encodedKey().equals(fixture.sparseKey)),
        "No table bytes before root CKey");
  }

  static void pageIndexAndSourceIdentityFailuresAreClosed() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var fixture = new Fixture(false);
      fixture.corruptPage = mode == 0;
      fixture.corruptIndex = mode == 1;
      fixture.wrongIdentity = mode == 2;
      failure(fixture::run);
      check(
          fixture.events.stream().noneMatch(s -> s.startsWith("VERIFIED COMPLETE ROOT")),
          "Invalid proof chain never reaches root claims");
    }
  }

  static void refusalsHappenBeforeWireAndDecodeWork() throws Exception {
    var fixture = new Fixture(false);
    var defaults = ItemCohortProbe.Limits.defaults();
    var small = new ItemCohortProbe.Limits(100, 10, Duration.ofSeconds(1), 10000, 1024, 1024);
    failure(() -> fixture.probe(small).run(fixture.build, fixture.cdn));
    check(fixture.transfers.isEmpty(), "No range transfers after config budget refusal");
    var requests =
        new ItemCohortProbe.Limits(
            defaults.wireBytes(),
            2,
            defaults.time(),
            defaults.decodedBytes(),
            defaults.chunkBytes(),
            defaults.cacheBytes());
    failure(() -> fixture.probe(requests).run(fixture.build, fixture.cdn));
    check(fixture.transfers.isEmpty(), "Request budget enforced before first range");
    var clock = new AtomicLong();
    var budget = new ItemCohortProbe.Budget(defaults, clock::get, () -> false);
    clock.set(defaults.time().toNanos());
    failure(() -> budget.reserve(1));
    check(budget.requests == 0, "Time deadline enforced before transfer");
    var cancelled = new ItemCohortProbe.Budget(defaults, () -> 0L, () -> true);
    failure(() -> cancelled.reserve(1));
    check(cancelled.requests == 0, "Precancel performs no request");
    var work = new ItemCohortProbe.Budget(small, () -> 0L, () -> false);
    failure(() -> work.requireWork(101, 0, 1));
    check(work.requests == 0, "Preflight scope refusal has no side effect");
    failure(() -> work.decode(10001));
    check(work.decoded == 0, "Decode bound checked before accounting work");
  }

  static boolean rootBodyTransferred(Fixture fixture) {
    int header = ByteBuffer.wrap(fixture.objects.get(fixture.rootKey)).getInt(4);
    return fixture.transfers.stream()
        .anyMatch(r -> r.encodedKey().equals(fixture.rootKey) && r.start() >= header);
  }

  static void composedMemoryAndDecodeBudgetsRefuseBeforeTargetTransfers() throws Exception {
    var defaults = ItemCohortProbe.Limits.defaults();
    var perChunk = new Fixture(false);
    var chunkLimits =
        new ItemCohortProbe.Limits(
            defaults.wireBytes(),
            defaults.requests(),
            defaults.time(),
            defaults.decodedBytes(),
            204,
            defaults.cacheBytes());
    Throwable chunkError =
        failure(() -> perChunk.probe(chunkLimits).run(perChunk.build, perChunk.cdn));
    check(
        chunkError.getMessage().contains("chunk buffer budget"),
        "Composed table chunk refusal is explicit");
    check(
        perChunk.transfers.stream()
            .noneMatch(r -> r.encodedKey().equals(perChunk.itemKey) && r.start() >= 36),
        "Oversized table chunk never transferred");
    var cache = new Fixture(false);
    var cacheLimits =
        new ItemCohortProbe.Limits(
            defaults.wireBytes(),
            defaults.requests(),
            defaults.time(),
            defaults.decodedBytes(),
            defaults.chunkBytes(),
            1);
    failure(() -> cache.probe(cacheLimits).run(cache.build, cache.cdn));
    check(
        cache.transfers.stream()
            .noneMatch(r -> r.encodedKey().equals(cache.encodingKey) && r.count() == 23),
        "Cache refusal precedes first authenticated EN chunk transfer");
    var aggregate = new Fixture(false);
    var aggregateLimits =
        new ItemCohortProbe.Limits(
            defaults.wireBytes(),
            defaults.requests(),
            defaults.time(),
            2200,
            defaults.chunkBytes(),
            defaults.cacheBytes());
    failure(() -> aggregate.probe(aggregateLimits).run(aggregate.build, aggregate.cdn));
    check(
        !rootBodyTransferred(aggregate),
        "Aggregate root decode preflight prevents all root body transfers");
    check(
        aggregate.events.stream().noneMatch(event -> event.startsWith("VERIFIED ROOT MEMBERSHIP")),
        "Aggregate refusal withholds root memberships");
    var late = new Fixture(false, true);
    Throwable lateError = failure(() -> late.probe(chunkLimits).run(late.build, late.cdn));
    check(
        lateError.getMessage().contains("oversized later chunk=1"),
        "All root chunk bounds checked before streaming");
    check(
        !rootBodyTransferred(late),
        "An oversized late root chunk prevents earlier chunk transfers too");
  }

  public static void main(String[] args) throws Exception {
    happyPathRetainsVariantsWithoutWholeTableClaim();
    invalidRootCKeyWithholdsMembershipsAndTables();
    pageIndexAndSourceIdentityFailuresAreClosed();
    refusalsHappenBeforeWireAndDecodeWork();
    composedMemoryAndDecodeBudgetsRefuseBeforeTargetTransfers();
    System.out.println(
        "Item cohort synthetic self-test passed: "
            + checks
            + " assertions; zero network requests; no game content.");
  }
}
