package io.vibeminer.infrastructure.lookup;

import static io.vibeminer.infrastructure.lookup.MetadataRepairWorkerCompositionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.retention.*;
import io.vibeminer.domain.selectedfile.CanonicalReservation;
import io.vibeminer.infrastructure.acquisitioncatalog.SqliteBuildObjectCatalog;
import io.vibeminer.infrastructure.content.*;
import io.vibeminer.infrastructure.persistence.SqliteCatalog;
import io.vibeminer.infrastructure.retention.SqliteRetentionRepository;
import io.vibeminer.infrastructure.storage.FileObjectStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real process death after durable ROOT grammar, before repair/canonical completion. */
class MetadataRepairPostPromotionCrashTest {
  @TempDir Path directory;

  @Test
  void promotedOriginalSurvivesProcessDeathAndProofBoundOrdinaryReplayWithoutPayload()
      throws Exception {
    Path log = directory.resolve("child.log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                childClasspath(),
                CrashChild.class.getName(),
                directory.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Crash child timed out");
      assertEquals(83, child.exitValue(), () -> readLog(log));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Crash child did not retire");
      }
    }

    try (var store = new FileObjectStore(directory.resolve("store"))) {
      var sql = new SqliteCatalog(directory.resolve("catalog.sqlite"));
      ContentWorkerPermit abandoned;
      try (var c = sql.connect();
          var s = c.createStatement();
          var row = s.executeQuery("SELECT token,worker_nonce FROM canonical_content_slots")) {
        assertTrue(row.next());
        assertNotNull(row.getString(2), "Child must die with its actual worker admitted");
        abandoned =
            new ContentWorkerPermit(
                UUID.fromString(row.getString(1)), UUID.fromString(row.getString(2)));
        assertFalse(row.next());
      }
      var memberships = rows(sql, "build_object_memberships");
      var pins = rows(sql, "retention_scopes");
      var cap = store.contentSession();
      SqliteCanonicalContentRepository.recoverAtStartup(sql, cap);
      var claims = new SqliteCanonicalContentRepository(sql, store, cap);
      var audits = new SqliteMetadataRepairAuditRepository(sql, store, cap, CLOCK);
      UUID attempt;
      try (var c = sql.connect();
          var s = c.createStatement();
          var rows = s.executeQuery("SELECT attempt_id FROM metadata_repair_attempts")) {
        assertTrue(rows.next());
        attempt = UUID.fromString(rows.getString(1));
        assertFalse(rows.next());
      }
      var interrupted = audits.find(attempt, NONE).orElseThrow();
      var issued = interrupted.issued();
      var original = issued.originalProof();
      var request = issued.request();
      assertEquals(
          List.of(
              MetadataRepairAuditEvent.Kind.PROSPECTIVE, MetadataRepairAuditEvent.Kind.STRUCTURE),
          interrupted.events().stream().map(MetadataRepairAuditEvent::kind).toList());
      var claim = claims.lookup(request.slot(), NONE).orElseThrow();
      assertEquals(issued.reservationToken(), claim.token());
      assertEquals(CanonicalReservation.Status.RECOVERY, claim.status());
      assertEquals(Optional.of(original), claim.proof());
      assertEquals(abandoned.reservationToken(), claim.token());
      assertThrows(ContentClaimException.class, () -> claims.markPublishing(abandoned, NONE));
      var structure = interrupted.events().getLast().observation().orElseThrow();
      assertThrows(
          ContentClaimException.class,
          () -> audits.recordStructure(abandoned, attempt, structure, NONE));
      assertTrue(store.verify(original.object().digest(), NONE).isPresent());
      assertEquals(2, count(sql, "build_object_memberships"));
      assertEquals(1, count(sql, "retention_scopes"));
      assertEquals(0, count(sql, "metadata_build_associations"));
      assertOtherEncodingRefused(claims, request, original);

      var noHttp = new RootLookupFixture();
      noHttp.rejectNetwork = true;
      try (var roots = new OfficialFileLookupGateway(store, noHttp, CLOCK)) {
        var alias =
            new MetadataContentAcquisition(claims)
                .acquire(
                    request,
                    original.originalAlias().source(),
                    Optional::empty,
                    (observer, token) -> {
                      throw new AssertionError("Recovery cannot acquire payload");
                    },
                    (retained, observer, token) ->
                        roots.lookupRetained(
                            request.build(),
                            request.configuration(),
                            retained,
                            Set.of(),
                            LookupBudgets.defaults(),
                            observer,
                            token),
                    NONE);
        assertEquals(original.originalAlias(), alias);
        var recovered = claims.lookup(request.slot(), NONE).orElseThrow();
        assertEquals(claim.token(), recovered.token());
        assertEquals(CanonicalReservation.Status.RETAINED, recovered.status());
        var proof = (MetadataContentProof) recovered.proof().orElseThrow();
        assertEquals(original.originalAlias(), proof.originalAlias());
        assertEquals(original.actualEncodingKey(), proof.actualEncodingKey());
        assertEquals(original.declaredDecodedBytes(), proof.declaredDecodedBytes());
        assertTrue(proof.decodedAttestation().isPresent());
        assertTrue(proof.structuralAttestation().isPresent());
        assertEquals(
            2,
            roots
                .lookupRetained(
                    request.build(),
                    request.configuration(),
                    alias,
                    Set.of(42L),
                    LookupBudgets.defaults(),
                    NONE)
                .variants()
                .size());
        assertTrue(noHttp.requests.isEmpty());
        assertOtherEncodingRefused(claims, request, original);
      }
      assertEquals(interrupted, audits.find(attempt, NONE).orElseThrow());
      assertEquals(memberships, rows(sql, "build_object_memberships"));
      assertEquals(pins, rows(sql, "retention_scopes"));
      assertEquals(2, count(sql, "build_object_memberships"));
      assertEquals(1, count(sql, "retention_scopes"));
      assertEquals(0, count(sql, "metadata_build_associations"));
    }
  }

  static void assertOtherEncodingRefused(
      SqliteCanonicalContentRepository claims,
      MetadataContentRequest request,
      MetadataContentProof original) {
    String other =
        original.actualEncodingKey().equals("f".repeat(32)) ? "e".repeat(32) : "f".repeat(32);
    assertThrows(
        ContentClaimException.class,
        () -> claims.reserve(request.slot(), other, original.encodedBytes(), NONE));
    assertEquals(
        Optional.of(original.originalAlias()),
        claims
            .lookup(request.slot(), NONE)
            .orElseThrow()
            .proof()
            .map(value -> ((MetadataContentProof) value).originalAlias()));
  }

  static List<List<String>> rows(SqliteCatalog sql, String table) throws Exception {
    var result = new ArrayList<List<String>>();
    try (var c = sql.connect();
        var s = c.createStatement();
        var rows = s.executeQuery("SELECT * FROM " + table + " ORDER BY rowid")) {
      while (rows.next()) {
        var values = new ArrayList<String>();
        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
          values.add(rows.getString(column));
        result.add(values);
      }
    }
    return result;
  }

  static String readLog(Path log) {
    try {
      return Files.readString(log);
    } catch (Exception failed) {
      return failed.toString();
    }
  }

  static String childClasspath() throws Exception {
    var paths = new LinkedHashSet<String>();
    for (Class<?> type :
        List.of(
            CrashChild.class,
            MetadataContentAcquisition.class,
            StoredObject.class,
            io.vibeminer.protocol.tact.blte.BlteDecoder.class,
            io.vibeminer.format.wow.root.SelectedRootLookup.class,
            org.sqlite.JDBC.class,
            org.junit.jupiter.api.Assertions.class,
            org.opentest4j.AssertionFailedError.class,
            org.junit.platform.commons.util.Preconditions.class)) {
      paths.add(
          Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
    paths.add(
        Path.of(FileObjectStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString());
    return String.join(java.io.File.pathSeparator, paths);
  }

  public static final class CrashChild {
    public static void main(String[] args) throws Exception {
      // Halt is confined to this explicitly launched test process; no finally/close can run.
      try (var f = new Fixture(Path.of(args[0]), 1)) {
        var requestPlan = f.selection.plan();
        var builds = new SqliteBuildObjectCatalog(f.sql, f.store);
        var alias = f.original.originalAlias();
        builds.attachVerifiedObject(
            requestPlan.build().snapshotId(),
            "ROOT_MANIFEST",
            alias.source(),
            alias.object(),
            alias.evidence(),
            NONE);
        var cfg = requestPlan.configuration();
        var scope =
            new RetentionScope(
                requestPlan.build().snapshotId(),
                ScopeKind.ROOT_METADATA,
                List.of(
                    new ScopeRequirement(
                        "BUILD_CONFIG",
                        Optional.of(cfg.source()),
                        OptionalLong.of(cfg.object().length()),
                        OptionalLong.empty()),
                    new ScopeRequirement(
                        "ROOT_MANIFEST",
                        Optional.of(alias.source()),
                        OptionalLong.of(alias.object().length()),
                        f.original.declaredDecodedBytes())));
        new SqliteRetentionRepository(f.sql).pin(scope, NONE);
        Files.delete(f.objectPath());
        var local = new OfficialMetadataLocalVerificationGateway(f.store);
        var declarations = new OfficialMetadataReuseGateway(f.store);
        var audit =
            (MetadataRepairAuditRepository)
                Proxy.newProxyInstance(
                    MetadataRepairAuditRepository.class.getClassLoader(),
                    new Class<?>[] {MetadataRepairAuditRepository.class},
                    (proxy, method, arguments) -> {
                      try {
                        var value = method.invoke(f.audits, arguments);
                        if (method.getName().equals("recordStructure")) {
                          assertTrue(Files.exists(f.objectPath()));
                          assertTrue(
                              f.store.verify(f.original.object().digest(), NONE).isPresent());
                          Runtime.getRuntime().halt(83);
                        }
                        return value;
                      } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                      }
                    });
        try (var service =
            new DefaultMetadataRepairService(
                f.issuer,
                new OfficialMetadataRepairExecutionGateway(f.issuer, f.store, f.wire, CLOCK),
                local,
                declarations,
                builds,
                f.claims,
                audit,
                new SqliteMetadataAssociationRepository(f.sql, f.store, f.store.contentSession()),
                f.store,
                Runnable::run,
                CLOCK,
                System::nanoTime)) {
          var issued = service.prepareRoot(f.selection).toCompletableFuture().join();
          service.execute(issued).toCompletableFuture().join();
          throw new AssertionError("Crash checkpoint was not reached");
        }
      }
    }
  }
}
