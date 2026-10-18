package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.application.buildoverview.BuildOverview.*;
import static io.vibeminer.infrastructure.inventory.SqliteBuildInventoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.encodedsource.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import io.vibeminer.infrastructure.acquisition.*;
import io.vibeminer.infrastructure.buildoverview.SqliteBuildOverviewBackend;
import io.vibeminer.infrastructure.buildretention.*;
import io.vibeminer.infrastructure.encodedsource.OfficialEncodedSourcePublisher;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Authored source documents through real parsers/CAS/SQL; no official/live TACT attestation. */
class SqliteBuildOverviewBackendTest {
  @TempDir Path directory;
  SqliteBuildInventoryTest owner;
  SqliteBuildInventoryTest.Fixture f;
  SqliteBuildRetentionInventory authority;
  SqliteBuildOverviewBackend backend;
  OfficialEncodedSourcePublisher publisher;
  NoHttp client = new NoHttp();
  static final EncodedSourcePlan.Limits ENCODED =
      new EncodedSourcePlan.Limits(4096, 100, 1000000, 1024, 100, 1000000, Duration.ofSeconds(30));

  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    owner = new SqliteBuildInventoryTest();
    owner.directory = directory;
    f =
        owner
        .new Fixture(
            false,
            false,
            false,
            false,
            false,
            testInfo.getTestMethod().orElseThrow().getName().startsWith("admittedMetadata"));
    try (var c = f.sql.connect()) {
      BuildRetentionBindingSchema.migrate(c);
      BuildRetentionSourceSchema.install(c);
    }
    authority =
        new SqliteBuildRetentionInventory(
            f.sql,
            f.store,
            f.admissions,
            new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
            budgets(),
            EN_LIMITS,
            new SqliteBuildRetentionInventory.ReservationPolicy(
                SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults()));
    publisher =
        new OfficialEncodedSourcePublisher(
            f.store,
            new PinnedCdnRangeTransport(
                client, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1)));
    backend =
        new SqliteBuildOverviewBackend(
            f.sql,
            f.store,
            f.admissions,
            authority,
            publisher,
            ENCODED,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void close() {
    authority.close();
    f.close();
    assertEquals(0, client.requests.get());
  }

  Request request(Optional<BuildRetention.Target> target) {
    return new Request(
        f.facts.reference(),
        f.facts.locale(),
        f.facts.scopeRevision(),
        new Limits(10000, 10000000, 128, 10000000, Duration.ofSeconds(30)),
        target);
  }

  BuildRetention.Target target() throws Exception {
    var prepared =
        f.inventory.prepare(
            f.facts.reference(),
            f.facts.locale(),
            f.facts.scopeRevision(),
            budgets(),
            EN_LIMITS,
            SqliteBuildInventoryTest.LIMITS,
            NONE);
    return authority.issueTarget(
        f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), prepared, NONE);
  }

  ContentRepresentation retain(BuildRetention.Target target) throws Exception {
    var bytes = blte(new byte[100]);
    var object =
        f.store.publishVerified(
            new ByteArrayInputStream(bytes), bytes.length, bytes.length, t -> {}, NONE);
    var slot = new ContentSlotKey("wow", f.payloadC);
    String url = "https://fixture.invalid/data/" + f.payloadE;
    var actual =
        new ContentRepresentation(
            slot,
            new VerifiedSourceAlias(
                ContentRepresentation.encodedIdentity(f.payloadE),
                object,
                new SourceVerificationEvidence(ContentRepresentation.VERIFIER_SEMANTICS, url, NOW)),
            EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS,
            new EncodedSourceReceipt(
                EncodedSourceReceipt.Kind.LOOSE,
                url,
                f.payloadE,
                bytes.length,
                0,
                bytes.length,
                NOW,
                1,
                bytes.length,
                "e".repeat(64),
                Optional.empty()));
    publisher.inspect(actual, ENCODED, NONE);
    var claim = f.canonical.reserve(slot, f.payloadE, bytes.length, NONE);
    var proof = new EncodedContentProof(actual);
    f.canonical.markPublishing(claim.token(), NONE);
    f.canonical.recordVerifiedCompletion(claim.token(), proof, NONE);
    f.canonical.completePublication(claim.token(), proof, NONE);
    try (var opened = authority.openSealed(target, NONE);
        var source = authority.openSource(target, opened.members(0, 1, NONE).getFirst(), NONE)) {
      new SqliteBuildRetentionSourceAssociations(f.sql, f.store)
          .attach(source, target, source.member(), actual, NONE);
    }
    return actual;
  }

  @Test
  void absentIssuedTargetReadsMetadataWithoutPreparingOrIssuingInventory() throws Exception {
    var before = count("build_inventory_generations");
    var result = backend.read(request(Optional.empty()), p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertEquals(MetadataState.READY, result.metadata());
    assertEquals(InventoryState.UNKNOWN, result.inventory());
    assertTrue(result.measurements().plannedEncodedBytes().isEmpty());
    assertEquals(before, count("build_inventory_generations"));
    assertEquals(0, count("build_retention_bindings"));
  }

  @Test
  void actualEncodedPhysicalClosureAndMembershipProduceFullWithHonestCounts() throws Exception {
    var target = target();
    var actual = retain(target);
    var observations = new ArrayList<Progress>();
    var result = backend.read(request(Optional.of(target)), observations::add, NONE);
    assertEquals(Readiness.FULL, result.readiness());
    assertEquals(1, result.measurements().acquisitionUnits().orElseThrow());
    assertEquals(2, result.measurements().variantMemberships().orElseThrow());
    assertEquals(
        actual.encoded().object().length(),
        result.measurements().plannedEncodedBytes().orElseThrow());
    assertEquals(
        result.measurements().plannedEncodedBytes(),
        result.measurements().retainedScopeEncodedBytes());
    assertTrue(result.measurements().localPhysicalBytes().isEmpty());
    assertTrue(result.measurements().distinctFileIds().isEmpty());
    assertEquals(target, result.target().orElseThrow());
    assertEquals(f.facts.scopeRevision(), result.scopeRevision());
    assertEquals(Stage.COMPLETE, observations.getLast().stage());
  }

  @Test
  void missingCurrentMembershipCannotBeReplacedByRetainedCanonicalOrJobHistory() throws Exception {
    var target = target();
    var result = backend.read(request(Optional.of(target)), p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertEquals(OptionalLong.of(0), result.measurements().retainedUnits());
    assertTrue(result.problems().stream().anyMatch(p -> p.blocksFull()));
  }

  @Test
  void missingOrCorruptCurrentPhysicalBytesRevokeFull() throws Exception {
    var target = target();
    var actual = retain(target);
    var path =
        directory
            .resolve("objects/objects")
            .resolve(actual.encoded().object().digest().value().substring(0, 2))
            .resolve(actual.encoded().object().digest().value().substring(2));
    // Resolve the actual owned CAS leaf instead of assuming an external installation location.
    try (var files = Files.walk(directory.resolve("objects"))) {
      path =
          files
              .filter(Files::isRegularFile)
              .filter(
                  p ->
                      p.getFileName()
                          .toString()
                          .contains(actual.encoded().object().digest().value().substring(2)))
              .findFirst()
              .orElse(path);
    }
    assertTrue(Files.exists(path));
    Files.write(path, new byte[] {9, 9, 9});
    var result = backend.read(request(Optional.of(target)), p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertEquals(OptionalLong.of(0), result.measurements().retainedUnits());
    assertTrue(result.problems().stream().anyMatch(p -> p.code() == ProblemCode.RETAINED_CORRUPT));
  }

  @Test
  void zeroVerificationBudgetPreservesUnknownRetainedTotals() throws Exception {
    var target = target();
    retain(target);
    var ordinary = request(Optional.of(target));
    var limited =
        new Request(
            ordinary.reference(),
            ordinary.locale(),
            ordinary.scopeRevision(),
            new Limits(10000, 0, 128, 10000000, Duration.ofSeconds(30)),
            ordinary.target());
    var result = backend.read(limited, p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertTrue(result.measurements().retainedUnits().isEmpty());
    assertFalse(result.measurements().verificationComplete());
    assertTrue(result.problems().stream().anyMatch(p -> p.code() == ProblemCode.LIMIT));
  }

  @Test
  void retainedCanonicalWithoutExactSourceAssociationIsPartial() throws Exception {
    var target = target();
    retain(target);
    try (var c = f.sql.connect();
        var q = c.createStatement()) {
      q.execute("DROP TRIGGER build_retention_sources_immutable_delete");
      q.execute("DELETE FROM build_retention_sources");
    }
    var result = backend.read(request(Optional.of(target)), p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertEquals(OptionalLong.of(0), result.measurements().retainedUnits());
  }

  @Test
  void lateCanonicalChangeCannotLeaveFullAfterPhysicalPass() throws Exception {
    var target = target();
    retain(target);
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    var result =
        backend.read(
            request(Optional.of(target)),
            p -> {
              if (p.stage() == Stage.CHECKING_RETAINED && changed.compareAndSet(false, true))
                try (var c = f.sql.connect();
                    var q = c.createStatement()) {
                  q.execute(
                      "UPDATE canonical_content_slots SET status='RECOVERY' WHERE content_key='"
                          + f.payloadC
                          + "'");
                } catch (Exception e) {
                  throw new AssertionError(e);
                }
            },
            NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertFalse(result.measurements().verificationComplete());
    assertTrue(result.problems().stream().anyMatch(p -> p.code() == ProblemCode.SOURCE_CHANGED));
  }

  @Test
  void cancellationAfterPhysicalProgressHasNoTerminalFull() throws Exception {
    var target = target();
    retain(target);
    var cancel = new java.util.concurrent.atomic.AtomicBoolean();
    var seen = new ArrayList<Progress>();
    assertThrows(
        CancellationException.class,
        () ->
            backend.read(
                request(Optional.of(target)),
                p -> {
                  seen.add(p);
                  if (p.stage() == Stage.CHECKING_RETAINED) cancel.set(true);
                },
                cancel::get));
    assertTrue(seen.stream().noneMatch(p -> p.stage() == Stage.COMPLETE));
  }

  @Test
  void memberLimitDoesNotTurnUnknownDenominatorIntoZero() throws Exception {
    var target = target();
    var ordinary = request(Optional.of(target));
    var limited =
        new Request(
            ordinary.reference(),
            ordinary.locale(),
            ordinary.scopeRevision(),
            new Limits(0, 1000, 1, 1000000, Duration.ofSeconds(30)),
            ordinary.target());
    var result = backend.read(limited, p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertEquals(OptionalLong.of(1), result.measurements().acquisitionUnits());
    assertTrue(result.measurements().plannedEncodedBytes().isEmpty());
    assertTrue(result.measurements().retainedUnits().isEmpty());
  }

  @Test
  void historicalSuccessfulJobIsNotCurrentPhysicalReadiness() throws Exception {
    var target = target();
    // Authored durable history, deliberately independent of any current retained source proof.
    try (var c = f.sql.connect();
        var opened = authority.openSealed(target, NONE)) {
      var member = opened.members(0, 1, NONE).getFirst();
      try (var q =
          c.prepareStatement(
              "INSERT INTO"
                  + " build_retention_intents(target_id,group_id,snapshot_id,admission_id,inventory_id,inventory_generation,scope_revision,scope_commitment,mode,generation,authorized_at,member_count,next_ordinal,maximum_object,maximum_wire,maximum_additional,maximum_requests,maximum_attempts,reserved_wire,reserved_additional,reserved_requests)"
                  + " VALUES(?,?,?,?,?,?,?,?,'DOWNLOAD_ALL',1,?,1,1,10000000,1000000000,1000000000,1000000,2,0,0,0)")) {
        Object[] values = {
          target.identity(),
          target.groupId(),
          target.snapshotId(),
          target.admissionId(),
          target.inventoryId(),
          target.inventoryGeneration(),
          target.scopeRevision(),
          target.scopeCommitment(),
          NOW.toString()
        };
        for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
        q.executeUpdate();
      }
      try (var q =
          c.prepareStatement(
              "INSERT INTO"
                  + " build_retention_jobs(target_id,member_id,ordinal,content_key,encoding_key,encoded_bytes,cost_wire,cost_additional,cost_requests,membership_commitment,state,attempts,fulfillment_proof)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,'SUCCEEDED',1,?)")) {
        Object[] values = {
          target.identity(),
          member.memberId(),
          member.ordinal(),
          member.contentKey(),
          member.encodingKey(),
          member.encodedBytes(),
          member.cost().wireBytes(),
          member.cost().additionalBytes(),
          member.cost().requests(),
          member.membershipCommitment(),
          "1".repeat(64)
        };
        for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
        q.executeUpdate();
      }
    }
    var result = backend.read(request(Optional.of(target)), p -> {}, NONE);
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertEquals(OptionalLong.of(0), result.measurements().retainedUnits());
    assertEquals(OptionalLong.of(1), result.retention().orElseThrow().succeeded());
    assertEquals(BuildRetention.Mode.DOWNLOAD_ALL, result.retention().orElseThrow().mode());
  }

  @Test
  void admittedMetadataMemberIsFullWithoutInventingEncodedReceipt() throws Exception {
    var target = target();
    long receipts = count("encoded_file_receipts"), associations = count("build_retention_sources");
    var result = backend.read(request(Optional.of(target)), p -> {}, NONE);
    assertEquals(Readiness.FULL, result.readiness());
    assertEquals(OptionalLong.of(1), result.measurements().retainedUnits());
    assertEquals(OptionalLong.of(47), result.measurements().retainedScopeEncodedBytes());
    assertEquals(receipts, count("encoded_file_receipts"));
    assertEquals(associations, count("build_retention_sources"));
    try (var c = f.sql.connect();
        var q =
            c.prepareStatement(
                "SELECT representation FROM canonical_content_slots WHERE content_key=?")) {
      q.setString(1, f.payloadC);
      try (var r = q.executeQuery()) {
        assertTrue(r.next());
        assertInstanceOf(
            io.vibeminer.domain.content.MetadataContentProof.class,
            io.vibeminer.infrastructure.content.CanonicalContentRecords.decode(r.getBytes(1)));
      }
    }
  }

  @Test
  void ownDeadlineDuringPhysicalReplayDoesNotLabelHealthyPayloadCorrupt() throws Exception {
    var target = target();
    retain(target);
    assertEquals(
        Readiness.FULL, backend.read(request(Optional.of(target)), p -> {}, NONE).readiness());
    var entered = new java.util.concurrent.atomic.AtomicBoolean();
    ObjectStore delayed = deadlinePollingStore(entered);
    var delayedPublisher =
        new OfficialEncodedSourcePublisher(
            delayed,
            new PinnedCdnRangeTransport(
                client, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1)));
    var delayedBackend =
        new SqliteBuildOverviewBackend(
            f.sql,
            f.store,
            f.admissions,
            authority,
            delayedPublisher,
            ENCODED,
            Clock.fixed(NOW, ZoneOffset.UTC));
    var base = request(Optional.of(target));
    var limited =
        new Request(
            base.reference(),
            base.locale(),
            base.scopeRevision(),
            new Limits(10000, 10000000, 128, 10000000, Duration.ofSeconds(1)),
            base.target());
    var result = delayedBackend.read(limited, p -> {}, NONE);
    assertTrue(entered.get());
    assertEquals(Readiness.PARTIAL, result.readiness());
    assertTrue(result.problems().stream().anyMatch(p -> p.code() == ProblemCode.LIMIT));
    assertTrue(result.problems().stream().noneMatch(p -> p.code() == ProblemCode.RETAINED_CORRUPT));
  }

  @Test
  void enabledIntentBeforeFirstQueueFillPreservesActualEnumerationPending() throws Exception {
    var target = target();
    try (var c = f.sql.connect();
        var q =
            c.prepareStatement(
                "INSERT INTO"
                    + " build_retention_intents(target_id,group_id,snapshot_id,admission_id,inventory_id,inventory_generation,scope_revision,scope_commitment,mode,generation,authorized_at,member_count,next_ordinal,maximum_object,maximum_wire,maximum_additional,maximum_requests,maximum_attempts,reserved_wire,reserved_additional,reserved_requests)"
                    + " VALUES(?,?,?,?,?,?,?,?,'DOWNLOAD_ALL',1,?,1,0,10000000,1000000000,1000000000,1000000,2,0,0,0)")) {
      Object[] values = {
        target.identity(),
        target.groupId(),
        target.snapshotId(),
        target.admissionId(),
        target.inventoryId(),
        target.inventoryGeneration(),
        target.scopeRevision(),
        target.scopeCommitment(),
        NOW.toString()
      };
      for (int i = 0; i < values.length; i++) q.setObject(i + 1, values[i]);
      q.executeUpdate();
    }
    var before =
        backend.read(request(Optional.of(target)), p -> {}, NONE).retention().orElseThrow();
    assertFalse(before.enumerationComplete());
    assertEquals(OptionalLong.of(0), before.queued());
    assertEquals(OptionalLong.of(0), before.running());
    try (var c = f.sql.connect();
        var q = c.createStatement()) {
      q.execute("UPDATE build_retention_intents SET next_ordinal=member_count");
    }
    var after = backend.read(request(Optional.of(target)), p -> {}, NONE).retention().orElseThrow();
    assertTrue(after.enumerationComplete());
    assertTrue(
        new Retention(
                BuildRetention.Mode.ON_DEMAND,
                1,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty())
            .enumerationComplete());
  }

  @Test
  void ownDeadlineDuringMetadataReplayIsLimitInsteadOfCorruptMetadata() throws Exception {
    var entered = new java.util.concurrent.atomic.AtomicBoolean();
    var delayed = deadlinePollingStore(entered);
    var delayedAdmissions =
        new io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionRepository(
            f.sql, delayed);
    var delayedBackend =
        new SqliteBuildOverviewBackend(
            f.sql,
            f.store,
            delayedAdmissions,
            authority,
            publisher,
            ENCODED,
            Clock.fixed(NOW, ZoneOffset.UTC));
    var base = request(Optional.empty());
    var limited =
        new Request(
            base.reference(),
            base.locale(),
            base.scopeRevision(),
            new Limits(10000, 10000000, 128, 10000000, Duration.ofSeconds(1)),
            base.target());
    var failure =
        assertThrows(
            io.vibeminer.application.buildoverview.BuildOverviewException.class,
            () -> delayedBackend.read(limited, p -> {}, NONE));
    assertTrue(entered.get());
    assertEquals(
        io.vibeminer.application.buildoverview.BuildOverviewException.Code.LIMIT, failure.code());
  }

  ObjectStore deadlinePollingStore(java.util.concurrent.atomic.AtomicBoolean entered) {
    return new ObjectStore() {
      public StoredObject publish(
          InputStream source,
          ExpectedObjectChecksum checksum,
          long maximum,
          CancellationToken token) {
        return f.store.publish(source, checksum, maximum, token);
      }

      public StoredObject publishVerified(
          InputStream source,
          long length,
          long maximum,
          CompletionVerification verification,
          CancellationToken token) {
        return f.store.publishVerified(source, length, maximum, verification, token);
      }

      public Optional<StoredObject> verify(ObjectDigest digest, CancellationToken token) {
        return f.store.verify(digest, token);
      }

      public ObjectReadLease openRead(ObjectDigest digest, CancellationToken token) {
        entered.set(true);
        while (true) {
          token.throwIfCancellationRequested();
          Thread.onSpinWait();
        }
      }

      public void close() {}
    };
  }

  long count(String table) throws Exception {
    try (var c = f.sql.connect();
        var q = c.createStatement();
        var r = q.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  static final class NoHttp extends HttpClient {
    final AtomicInteger requests = new AtomicInteger();

    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }

    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    public Version version() {
      return Version.HTTP_1_1;
    }

    public Optional<Executor> executor() {
      return Optional.empty();
    }

    public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> b) {
      requests.incrementAndGet();
      throw new AssertionError("No HTTP in overview");
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> b) {
      requests.incrementAndGet();
      throw new AssertionError("No HTTP in overview");
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> b, HttpResponse.PushPromiseHandler<T> p) {
      return sendAsync(r, b);
    }
  }
}
