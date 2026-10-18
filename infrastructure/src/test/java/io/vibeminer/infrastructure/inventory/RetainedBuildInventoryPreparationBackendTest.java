package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.infrastructure.inventory.SqliteBuildInventoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.inventory.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real authored SQL/CAS metadata proofs and sealed members; no transport is supplied. */
class RetainedBuildInventoryPreparationBackendTest {
  @TempDir Path directory;
  private SqliteBuildInventoryTest.Fixture fixture;

  @BeforeEach
  void setup() throws Exception {
    var owner = new SqliteBuildInventoryTest();
    owner.directory = directory;
    fixture = owner.new Fixture(false, false, false);
    try (var db = fixture.sql.connect()) {
      BuildRetentionBindingSchema.migrate(db);
    }
  }

  @AfterEach
  void close() {
    fixture.close();
  }

  private SqliteBuildRetentionInventory authority() {
    return authority(fixture, ArchiveSearchBudgets.defaults());
  }

  private SqliteBuildRetentionInventory authority(
      SqliteBuildInventoryTest.Fixture f, ArchiveSearchBudgets archive) {
    return new SqliteBuildRetentionInventory(
        f.sql,
        f.store,
        f.admissions,
        new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
        budgets(),
        EN_LIMITS,
        new SqliteBuildRetentionInventory.ReservationPolicy(
            SelectedFileBudgets.defaults(), archive));
  }

  private RetainedBuildInventoryPreparationBackend backend(
      SqliteBuildRetentionInventory authority, SqliteBuildInventory.Limits limits) {
    return backend(fixture, authority, limits);
  }

  private RetainedBuildInventoryPreparationBackend backend(
      SqliteBuildInventoryTest.Fixture f,
      SqliteBuildRetentionInventory authority,
      SqliteBuildInventory.Limits limits) {
    return new RetainedBuildInventoryPreparationBackend(
        f.admissions, f.inventory, authority, budgets(), EN_LIMITS, limits);
  }

  private long bindings() throws Exception {
    return bindings(fixture);
  }

  private long bindings(SqliteBuildInventoryTest.Fixture f) throws Exception {
    try (var db = f.sql.connect();
        var query = db.createStatement();
        var row = query.executeQuery("SELECT count(*) FROM build_retention_bindings")) {
      return row.getLong(1);
    }
  }

  private SqliteBuildInventoryTest.Fixture isolated(
      String name, boolean missingMapping, boolean zeroEncoded) throws Exception {
    var owner = new SqliteBuildInventoryTest();
    owner.directory = directory.resolve(name);
    var f = owner.new Fixture(false, missingMapping, false, zeroEncoded);
    try (var db = f.sql.connect()) {
      BuildRetentionBindingSchema.migrate(db);
    }
    return f;
  }

  private Path objectPath(StoredObject object) {
    var digest = object.digest().value();
    return directory
        .resolve("objects/objects")
        .resolve(digest.substring(0, 2))
        .resolve(digest.substring(2));
  }

  private static BuildMetadataAdmission withVerified(
      BuildMetadataAdmission admission, List<BuildMetadataAdmission.VerifiedRequirement> verified) {
    return new BuildMetadataAdmission(
        admission.reference(),
        admission.locale(),
        admission.scopeRevision(),
        admission.required(),
        admission.download(),
        admission.size(),
        admission.locators(),
        verified);
  }

  @Test
  void actualSealedInventoryIssuesStableTargetAndPolicyFromActualMemberReservations()
      throws Exception {
    var admission = fixture.facts.admission();
    var progress = new ArrayList<BuildInventoryPreparationProgress>();
    BuildInventoryPreparationResult first;
    try (var authority = authority()) {
      var backend = backend(authority, LIMITS);
      var result = backend.prepare(admission, progress::add, NONE);
      first = result;
      assertEquals(admission, result.admission());
      assertEquals(admission.reference().key().identity(), result.target().groupId());
      assertEquals(admission.reference().snapshotId(), result.target().snapshotId());
      assertTrue(result.downloadPolicy().isPresent());
      try (var sealed = authority.openSealed(result.target(), NONE)) {
        assertEquals(1, sealed.seal().memberCount());
        var members = sealed.members(0, 128, NONE);
        assertEquals(1, members.size());
        var member = members.getFirst();
        assertEquals(fixture.payloadC, member.contentKey());
        assertEquals(fixture.payloadE, member.encodingKey());
        var policy = result.downloadPolicy().orElseThrow();
        assertEquals(member.encodedBytes(), policy.maximumObjectBytes());
        assertEquals(Math.multiplyExact(member.cost().wireBytes(), 3), policy.maximumWireBytes());
        assertEquals(
            Math.multiplyExact(member.cost().additionalBytes(), 3),
            policy.maximumAdditionalBytes());
        assertEquals(Math.multiplyExact(member.cost().requests(), 3), policy.maximumRequests());
        assertEquals(3, policy.maximumAttempts());
        assertTrue(sealed.members(1, 128, NONE).isEmpty());
      }
      assertEquals(result, backend.prepare(admission, ignored -> {}, NONE));
      assertEquals(1, bindings());
      assertFalse(progress.isEmpty());
      assertEquals(
          BuildInventoryPreparationProgress.Stage.ISSUING_TARGET, progress.getLast().stage());
      for (var item : progress) {
        assertTrue(
            item.processedReferences().isEmpty(), "No unmeasured local-origin count is fabricated");
        assertTrue(item.totalReferences().isEmpty());
        assertTrue(item.failure().isEmpty());
        assertTrue(
            item.stage().ordinal() < BuildInventoryPreparationProgress.Stage.COMPLETE.ordinal());
      }
    }
    try (var reopened = authority()) {
      var reused = backend(reopened, LIMITS).prepare(admission, ignored -> {}, NONE);
      assertEquals(first, reused, "New local authority reuses the same durable target and policy");
      assertEquals(1, bindings());
      try (var sealed = reopened.openSealed(reused.target(), NONE)) {
        assertEquals(fixture.payloadC, sealed.members(0, 128, NONE).getFirst().contentKey());
      }
    }
  }

  @Test
  void sameContextForgedObjectFactsRefuseBeforeAnyTargetIssuance() throws Exception {
    var admission = fixture.facts.admission();
    var verified = new ArrayList<>(admission.verified());
    var original = verified.getFirst();
    verified.set(
        0,
        new BuildMetadataAdmission.VerifiedRequirement(
            original.requirement(),
            new StoredObject(new ObjectDigest("0".repeat(64)), original.object().length()),
            original.proof()));
    try (var authority = authority()) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () ->
                  backend(authority, LIMITS)
                      .prepare(withVerified(admission, verified), ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.STALE_CONTEXT, error.code());
      assertEquals(0, bindings());
    }
  }

  @Test
  void unadmittedLocaleCannotReuseAnotherLocalesCommittedMetadata() throws Exception {
    var original = fixture.facts.admission();
    var otherLocale =
        new BuildMetadataAdmission(
            original.reference(),
            "deDE",
            original.scopeRevision(),
            original.required(),
            original.download(),
            original.size(),
            original.locators(),
            original.verified());
    try (var authority = authority()) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () -> backend(authority, LIMITS).prepare(otherLocale, ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.NOT_READY, error.code());
      assertEquals(0, bindings());
    }
  }

  @Test
  void missingActualCasObjectCannotIssueTargetFromLedgerFactsAlone() throws Exception {
    var admission = fixture.facts.admission();
    var root =
        admission.verified().stream()
            .filter(item -> item.requirement().role() == BuildMetadataAdmission.Role.ROOT)
            .findFirst()
            .orElseThrow();
    Files.delete(objectPath(root.object()));
    try (var authority = authority()) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () -> backend(authority, LIMITS).prepare(admission, ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.NOT_READY, error.code());
      assertNotNull(error.getCause());
      assertEquals(0, bindings());
    }
  }

  @Test
  void corruptActualCasBytesCannotBecomeReadyInventoryOrIssuedTarget() throws Exception {
    var admission = fixture.facts.admission();
    var root =
        admission.verified().stream()
            .filter(item -> item.requirement().role() == BuildMetadataAdmission.Role.ROOT)
            .findFirst()
            .orElseThrow();
    var path = objectPath(root.object());
    var bytes = Files.readAllBytes(path);
    bytes[bytes.length - 1] ^= 1;
    assertTrue(path.toFile().setWritable(true));
    Files.write(path, bytes);
    try (var authority = authority()) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () -> backend(authority, LIMITS).prepare(admission, ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.CORRUPT_INVENTORY, error.code());
      assertNotNull(error.getCause());
      assertEquals(0, bindings());
    }
  }

  @Test
  void unsupportedZeroEncodedDeclarationCannotBecomeAnEmptySuccessfulInventory() throws Exception {
    try (var f = isolated("zero-encoded", false, true);
        var authority = authority(f, ArchiveSearchBudgets.defaults())) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () ->
                  backend(f, authority, LIMITS).prepare(f.facts.admission(), ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.UNSUPPORTED, error.code());
      assertNotNull(error.getCause());
      assertEquals(0, bindings(f));
      assertEquals(0, f.count("build_inventory_generations", "status='READY'"));
    }
  }

  @Test
  void unresolvedActualEncodingMappingIsNotReadyRatherThanPartialInventory() throws Exception {
    try (var f = isolated("missing-mapping", true, false);
        var authority = authority(f, ArchiveSearchBudgets.defaults())) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () ->
                  backend(f, authority, LIMITS).prepare(f.facts.admission(), ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.NOT_READY, error.code());
      assertNotNull(error.getCause());
      assertEquals(0, bindings(f));
      assertEquals(0, f.count("build_inventory_generations", "status='READY'"));
    }
  }

  @Test
  void checkedReservationOverflowRefusesOfferedPolicyWithoutActivatingRetentionMode()
      throws Exception {
    var hugeArchive = new ArchiveSearchBudgets(1, Long.MAX_VALUE / 2, 1, Duration.ofMinutes(1), 1);
    try (var authority = authority(fixture, hugeArchive)) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () ->
                  backend(authority, LIMITS)
                      .prepare(fixture.facts.admission(), ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.LIMIT_EXCEEDED, error.code());
      assertInstanceOf(ArithmeticException.class, error.getCause());
      assertEquals(
          1, bindings(), "Actual issuance is immutable even when no policy can be offered");
      assertEquals(0, fixture.count("build_retention_intents", "1=1"));
    }
  }

  @Test
  void cancellationAtIssuingStageKeepsPreparedGenerationButDoesNotIssueTargetAndRetrySucceeds()
      throws Exception {
    var cancelled = new AtomicBoolean();
    try (var authority = authority()) {
      var backend = backend(authority, LIMITS);
      assertThrows(
          CancellationException.class,
          () ->
              backend.prepare(
                  fixture.facts.admission(),
                  progress -> {
                    if (progress.stage() == BuildInventoryPreparationProgress.Stage.ISSUING_TARGET)
                      cancelled.set(true);
                  },
                  cancelled::get));
      assertEquals(0, bindings());
      assertEquals(1, fixture.count("build_inventory_generations", "status='READY'"));
      var result = backend.prepare(fixture.facts.admission(), ignored -> {}, NONE);
      assertTrue(result.downloadPolicy().isPresent());
      assertEquals(1, bindings());
      try (var sealed = authority.openSealed(result.target(), NONE)) {
        assertEquals(1, sealed.seal().memberCount());
      }
    }
  }

  @Test
  void preCancelledPreparationDoesNotCreateGenerationOrIssuance() throws Exception {
    try (var authority = authority()) {
      assertThrows(
          CancellationException.class,
          () ->
              backend(authority, LIMITS)
                  .prepare(fixture.facts.admission(), ignored -> {}, () -> true));
      assertEquals(0, bindings());
      assertEquals(0, fixture.count("build_inventory_generations", "1=1"));
    }
  }

  @Test
  void actualInventoryReferenceQuotaIsTypedLimitAndNeverIssuesPartialGeneration() throws Exception {
    var tiny =
        new SqliteBuildInventory.Limits(
            1,
            LIMITS.maximumDerivedBytes(),
            LIMITS.batchRows(),
            LIMITS.maximumBatches(),
            LIMITS.root(),
            LIMITS.install());
    try (var authority = authority()) {
      var error =
          assertThrows(
              BuildInventoryPreparationException.class,
              () ->
                  backend(authority, tiny).prepare(fixture.facts.admission(), ignored -> {}, NONE));
      assertEquals(BuildInventoryPreparationException.Code.LIMIT_EXCEEDED, error.code());
      assertEquals(0, bindings());
      assertEquals(0, fixture.count("build_inventory_generations", "status='READY'"));
    }
  }
}
