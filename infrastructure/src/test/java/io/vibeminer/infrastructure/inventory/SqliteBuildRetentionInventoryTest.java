package io.vibeminer.infrastructure.inventory;

import static io.vibeminer.infrastructure.inventory.SqliteBuildInventoryTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildretention.*;
import io.vibeminer.application.selectedfile.*;
import io.vibeminer.infrastructure.encoding.SqliteEncodingLookupIndex;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Authored real CAS/SQL/parser authority fixture; no claim of live official transport evidence. */
class SqliteBuildRetentionInventoryTest {
  @TempDir Path directory;
  SqliteBuildInventoryTest owner;
  SqliteBuildInventoryTest.Fixture f;

  @BeforeEach
  void setup() throws Exception {
    owner = new SqliteBuildInventoryTest();
    owner.directory = directory;
    f = owner.new Fixture(false, false, false);
    try (var c = f.sql.connect()) {
      BuildRetentionBindingSchema.migrate(c);
    }
  }

  @AfterEach
  void close() {
    f.close();
  }

  SqliteBuildRetentionInventory adapter() {
    return new SqliteBuildRetentionInventory(
        f.sql,
        f.store,
        f.admissions,
        new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
        budgets(),
        EN_LIMITS,
        new SqliteBuildRetentionInventory.ReservationPolicy(
            SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults()));
  }

  SqliteBuildInventory.Prepared prepare() throws Exception {
    return f.inventory.prepare(
        f.facts.reference(),
        f.facts.locale(),
        f.facts.scopeRevision(),
        budgets(),
        EN_LIMITS,
        LIMITS,
        NONE);
  }

  @Test
  void realSealedGenerationIssuesStableRestartIdentityAndExactMember() throws Exception {
    var prepared = prepare();
    BuildRetention.Target target;
    try (var a = adapter()) {
      target =
          a.issueTarget(
              f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), prepared, NONE);
      assertEquals(
          target,
          a.issueTarget(
              f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), prepared, NONE));
      assertTrue(target.inventoryGeneration() > 0);
      try (var opened = a.openSealed(target, NONE)) {
        assertEquals(1, opened.seal().memberCount());
        var member = opened.members(0, 128, NONE).getFirst();
        assertEquals(f.payloadC, member.contentKey());
        assertEquals(f.payloadE, member.encodingKey());
        assertTrue(member.cost().wireBytes() > member.encodedBytes());
        assertEquals(member.encodedBytes(), member.cost().additionalBytes());
        assertTrue(opened.members(1, 128, NONE).isEmpty());
        assertThrows(BuildRetentionException.class, () -> opened.members(0, 129, NONE));
        try (var c = f.sql.connect()) {
          c.setAutoCommit(false);
          a.validateInTransaction(c, opened, NONE);
          assertEquals(1, opened.members(0, 1, NONE).size());
          c.rollback();
        }
      }
    }
    try (var a = adapter();
        var opened = a.openSealed(target, NONE)) {
      assertEquals(target, opened.seal().target());
      assertEquals(f.payloadC, opened.members(0, 1, NONE).getFirst().contentKey());
    }
  }

  @Test
  void craftedGenerationTargetAndMemberNeverGrantAuthority() throws Exception {
    var prepared = prepare();
    try (var a = adapter()) {
      assertThrows(
          RuntimeException.class,
          () ->
              a.issueTarget(
                  f.facts.reference(),
                  f.facts.locale(),
                  f.facts.scopeRevision(),
                  new SqliteBuildInventory.Prepared(
                      UUID.randomUUID().toString(), 1, prepared.revision()),
                  NONE));
      var t =
          a.issueTarget(
              f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), prepared, NONE);
      var fake =
          new BuildRetention.Target(
              t.groupId(),
              t.snapshotId(),
              t.admissionId(),
              t.inventoryId(),
              t.inventoryGeneration() + 1,
              t.scopeRevision(),
              t.scopeCommitment());
      assertThrows(BuildRetentionException.class, () -> a.openSealed(fake, NONE));
      try (var opened = a.openSealed(t, NONE)) {
        var m = opened.members(0, 1, NONE).getFirst();
        var wrong =
            new BuildRetention.Member(
                m.ordinal(),
                m.memberId(),
                m.contentKey(),
                "1".repeat(32),
                m.encodedBytes(),
                m.cost(),
                m.membershipCommitment());
        assertThrows(BuildRetentionException.class, () -> a.openSource(t, wrong, NONE));
        try (var source = a.openSource(t, m, NONE)) {
          assertEquals(f.facts.reference(), source.reference());
          assertEquals(
              io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionRepository.closureId(
                  f.facts),
              io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionRepository.closureId(
                  source.metadata()));
        }
      }
    }
  }

  @Test
  void cachedContextClosePreservesActiveBorrowAndRejectsNewAdmissions() throws Exception {
    var p = prepare();
    var a = adapter();
    var t = a.issueTarget(f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), p, NONE);
    var opened = a.openSealed(t, NONE);
    a.close();
    assertEquals(1, opened.members(0, 1, NONE).size());
    assertThrows(BuildRetentionException.class, () -> a.openSealed(t, NONE));
    opened.close();
    opened.close();
    assertThrows(BuildRetentionException.class, () -> opened.members(0, 1, NONE));
  }

  @Test
  void sourceAuthorityRechecksSameCallerTransactionAndRollback() throws Exception {
    var p = prepare();
    try (var a = adapter()) {
      var t =
          a.issueTarget(f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), p, NONE);
      try (var opened = a.openSealed(t, NONE);
          var c = f.sql.connect()) {
        c.setAutoCommit(false);
        a.validateInTransaction(c, opened, NONE);
        assertEquals(1, opened.members(0, 1, NONE).size());
        try (var s = c.createStatement()) {
          s.execute(
              "DELETE FROM build_object_memberships WHERE snapshot_id='" + t.snapshotId() + "'");
        }
        assertThrows(RuntimeException.class, () -> a.validateInTransaction(c, opened, NONE));
        c.rollback();
        a.validateInTransaction(c, opened, NONE);
        c.rollback();
      }
    }
  }

  @Test
  void corruptMembershipCannotBecomeFreshAuthoritativePayload() throws Exception {
    var p = prepare();
    try (var a = adapter()) {
      var target =
          a.issueTarget(f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), p, NONE);
      try (var opened = a.openSealed(target, NONE);
          var c = f.sql.connect();
          var st = c.createStatement()) {
        st.execute("DROP TRIGGER build_inventory_members_ready_UPDATE");
        st.execute("DROP TRIGGER build_inventory_members_ready_target");
        st.execute(
            "UPDATE build_inventory_members SET encoded_bytes=10.5 WHERE generation='"
                + p.generation()
                + "'");
        assertEquals(
            BuildRetentionException.Code.CORRUPT_STATE,
            assertThrows(BuildRetentionException.class, () -> opened.members(0, 1, NONE)).code());
      }
    }
  }

  @Test
  void currentSourceLeaseCacheDoesNotReopenMetadataForEachJob() throws Exception {
    var p = prepare();
    int[] reads = {0};
    var observing =
        (io.vibeminer.application.ObjectStore)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {io.vibeminer.application.ObjectStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("openRead")) reads[0]++;
                  try {
                    return method.invoke(f.store, args);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    try (var a =
        new SqliteBuildRetentionInventory(
            f.sql,
            observing,
            f.admissions,
            new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
            budgets(),
            EN_LIMITS,
            new SqliteBuildRetentionInventory.ReservationPolicy(
                SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults()))) {
      var target =
          a.issueTarget(f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), p, NONE);
      try (var first = a.openSealed(target, NONE)) {
        assertEquals(1, first.members(0, 1, NONE).size());
      }
      int verified = reads[0];
      assertTrue(verified > 0);
      try (var second = a.openSealed(target, NONE)) {
        assertEquals(1, second.members(0, 1, NONE).size());
      }
      assertEquals(
          verified,
          reads[0],
          "Held immutable-source leases are reused, not rehashed for every job");
    }
  }

  @Test
  void refreshSameBuildKeepsIssuedTargetAndFirstProvenance() throws Exception {
    var p = prepare();
    var history =
        new io.vibeminer.infrastructure.discoveryhistory.SqliteDiscoveryHistoryRepository(f.sql);
    var first = history.read(f.facts.reference().eventId(), NONE).orElseThrow();
    var refreshed =
        new io.vibeminer.infrastructure.discovery.DiscoveryObservationMapper()
            .map(
                UUID.randomUUID(),
                first.productId(),
                first.region(),
                NOW.plusSeconds(1),
                first.versions().source(),
                first.cdns().source());
    history.append(
        refreshed,
        io.vibeminer.application.discoveryhistory.DiscoveryHistoryLimits.defaults(),
        NONE);
    var registration =
        new io.vibeminer.infrastructure.discoveryhistory.SqliteBuildLibraryRepository(f.sql)
            .recordObservation(
                refreshed.eventId(),
                io.vibeminer.application.buildlibrary.BuildLibraryRegistration.Kind
                    .HISTORICAL_IMPORT,
                Optional.empty(),
                NOW.plusSeconds(1),
                NONE);
    var newReference = registration.references().getFirst();
    BuildRetention.Target original;
    try (var a = adapter()) {
      original =
          a.issueTarget(f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), p, NONE);
      var current = a.issueTarget(newReference, f.facts.locale(), f.facts.scopeRevision(), p, NONE);
      assertEquals(
          original,
          current,
          "Advertisement receipt is consumer provenance, not a new content scope");
      try (var opened = a.openSealed(current, NONE);
          var source = a.openSource(current, opened.members(0, 1, NONE).getFirst(), NONE)) {
        assertEquals(
            f.facts.reference(),
            source.reference(),
            "First actual binding provenance remains immutable");
      }
    }
    f.store.close();
    try (var store =
        new io.vibeminer.infrastructure.storage.FileObjectStore(directory.resolve("objects"))) {
      io.vibeminer.infrastructure.content.SqliteContentStartupRecovery.recoverAtStartup(
          f.sql, store.contentSession());
      var admissions =
          new io.vibeminer.infrastructure.buildopening.BuildMetadataAdmissionRepository(
              f.sql, store);
      try (var a =
          new SqliteBuildRetentionInventory(
              f.sql,
              store,
              admissions,
              new SqliteEncodingLookupIndex(f.sql, store, admissions, UUID.randomUUID()),
              budgets(),
              EN_LIMITS,
              new SqliteBuildRetentionInventory.ReservationPolicy(
                  SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults()))) {
        var ledger =
            new io.vibeminer.infrastructure.buildretention.SqliteBuildRetentionRepository(
                f.sql, store, store.contentSession(), a);
        var intent =
            ledger.setMode(
                original,
                0,
                BuildRetention.Mode.DOWNLOAD_ALL,
                new BuildRetention.Policy(1000000, 100000000, 100000000, 10000, 3),
                NOW,
                NONE);
        var refreshedTarget =
            a.issueTarget(newReference, f.facts.locale(), f.facts.scopeRevision(), p, NONE);
        assertEquals(
            intent,
            ledger.readIntent(refreshedTarget, NONE).orElseThrow(),
            "Refresh does not lose persisted scope mode");
      }
    }
  }

  @Test
  void failingLeaseCloseStillRetiresEveryMetadataAndEncodingLease() throws Exception {
    var p = prepare();
    boolean[] fail = {false};
    int[] closes = {0};
    var observing =
        (io.vibeminer.application.ObjectStore)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {io.vibeminer.application.ObjectStore.class},
                (proxy, method, args) -> {
                  try {
                    var result = method.invoke(f.store, args);
                    if (!method.getName().equals("openRead")) return result;
                    var delegate = (io.vibeminer.application.ObjectReadLease) result;
                    return new io.vibeminer.application.ObjectReadLease() {
                      public io.vibeminer.domain.StoredObject object() {
                        return delegate.object();
                      }

                      public java.io.InputStream stream() {
                        return delegate.stream();
                      }

                      public void close() {
                        delegate.close();
                        closes[0]++;
                        if (fail[0]) throw new IllegalStateException("Authored close failure");
                      }
                    };
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    var a =
        new SqliteBuildRetentionInventory(
            f.sql,
            observing,
            f.admissions,
            new SqliteEncodingLookupIndex(f.sql, f.store, f.admissions, UUID.randomUUID()),
            budgets(),
            EN_LIMITS,
            new SqliteBuildRetentionInventory.ReservationPolicy(
                SelectedFileBudgets.defaults(), ArchiveSearchBudgets.defaults()));
    var target =
        a.issueTarget(f.facts.reference(), f.facts.locale(), f.facts.scopeRevision(), p, NONE);
    try (var opened = a.openSealed(target, NONE)) {
      assertEquals(1, opened.members(0, 1, NONE).size());
    }
    int before = closes[0];
    fail[0] = true;
    var failure = assertThrows(IllegalStateException.class, a::close);
    assertEquals(
        6, closes[0] - before, "Both configs and ROOT/IN/DL/DS all close despite failures");
    assertEquals(5, failure.getSuppressed().length);
    assertDoesNotThrow(f.store::close, "EN lease must also retire after metadata close failures");
  }

  @Test
  void reservationCeilingOverflowIsTypedLimitWithoutTruncation() {
    var standard = ArchiveSearchBudgets.defaults();
    var policy =
        new SqliteBuildRetentionInventory.ReservationPolicy(
            SelectedFileBudgets.defaults(),
            new ArchiveSearchBudgets(
                standard.maximumArchives(),
                Long.MAX_VALUE,
                standard.maximumRequests(),
                standard.maximumDuration(),
                standard.maximumArchiveBytes()));
    assertEquals(
        BuildRetentionException.Code.LIMIT_EXCEEDED,
        assertThrows(BuildRetentionException.class, () -> policy.cost(100)).code());
  }
}
