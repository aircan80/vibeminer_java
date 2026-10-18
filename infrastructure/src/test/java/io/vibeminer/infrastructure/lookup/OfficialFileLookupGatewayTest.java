package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.lookup.*;
import io.vibeminer.domain.*;
import io.vibeminer.infrastructure.storage.FileObjectStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;

class OfficialFileLookupGatewayTest {
    @TempDir Path directory;

    @Test
    void explicitPlanRetainsOneEncodedRootAndOfflineLookupPreservesAllVariants() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var store = new FileObjectStore(directory);
                var gateway =
                        new OfficialFileLookupGateway(
                                store,
                                fixture,
                                Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
            var plan =
                    gateway.prepareRoot(
                            build,
                            Optional.empty(),
                            LookupBudgets.defaults(),
                            CancellationToken.none());
            assertEquals(1, plan.candidates().size());
            assertEquals(fixture.rootCKey, plan.contentKey());
            assertTrue(
                    fixture.requests.stream()
                            .noneMatch(request -> request.encodedKey().equals(fixture.rootEKey)));
            var selected = new RootSelectionPlan(plan, plan.candidates().getFirst());
            gateway.validateSelection(selected, CancellationToken.none());
            var root = gateway.acquireRoot(selected, CancellationToken.none());
            assertEquals(fixture.encodedRoot.length, root.object().length());
            try (var lease = store.openRead(root.object().digest(), CancellationToken.none())) {
                assertArrayEquals(fixture.encodedRoot, lease.stream().readAllBytes());
            }
            fixture.rejectNetwork = true;
            var result =
                    gateway.lookupRetained(
                            build,
                            plan.configuration(),
                            root,
                            Set.of(42L, 7L, 999L),
                            LookupBudgets.defaults(),
                            CancellationToken.none());
            assertEquals(5, result.scannedRecords());
            assertEquals(Set.of(999L), result.absentIds());
            assertEquals(3, result.variants().size());
            var variants =
                    result.variants().stream()
                            .filter(variant -> variant.fileDataId() == 42)
                            .toList();
            assertEquals(2, variants.size());
            assertEquals(Optional.of("0000000000000000"), variants.getFirst().nameHash());
            assertEquals(Optional.empty(), variants.getLast().nameHash());
            assertEquals(0x10000000, variants.getLast().rawFlagsFirst());
            assertEquals(8, variants.getLast().rawFlagsSecond());
            assertEquals(0x80, variants.getLast().rawFlagsThird());
        }
    }

    @Test
    void allEncodingCandidatesAndDeferredSpecificationsRemainVisibleWithoutPayloadRequests()
            throws Exception {
        for (boolean missing : List.of(false, true)) {
            var fixture = new RootLookupFixture(false, false, true, missing);
            var build = fixture.build("us.cdn.blizzard.com", "1.0");
            try (var store = new FileObjectStore(directory.resolve("case" + missing));
                    var gateway =
                            new OfficialFileLookupGateway(
                                    store,
                                    fixture,
                                    Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
                var plan =
                        gateway.prepareRoot(
                                build,
                                Optional.empty(),
                                LookupBudgets.defaults(),
                                CancellationToken.none());
                assertEquals(2, plan.candidates().size());
                assertEquals(fixture.rootEKey, plan.candidates().getFirst().encodingKey());
                assertEquals(0, plan.candidates().getFirst().specificationIndex());
                var other = plan.candidates().getLast();
                assertEquals(fixture.secondRootEKey, other.encodingKey());
                assertEquals(
                        missing ? -1 : fixture.objects.get(fixture.secondRootEKey).length,
                        other.encodedBytes());
                assertEquals(missing ? -1 : 1, other.specificationIndex());
                if (missing)
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new RootSelectionPlan(plan, other));
                assertTrue(
                        fixture.requests.stream()
                                .noneMatch(
                                        request ->
                                                request.encodedKey().equals(fixture.rootEKey)
                                                        || request.encodedKey()
                                                                .equals(fixture.secondRootEKey)));
            }
        }
    }

    @Test
    void boundedCanonicalSessionsRejectEvictedPlansAndChangedDecodedSizes() throws Exception {
        var fixture = new RootLookupFixture();
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        try (var store = new FileObjectStore(directory);
                var gateway =
                        new OfficialFileLookupGateway(
                                store,
                                fixture,
                                Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
            var first =
                    gateway.prepareRoot(
                            build,
                            Optional.empty(),
                            LookupBudgets.defaults(),
                            CancellationToken.none());
            var changed =
                    new RootPlan(
                            first.sessionId(),
                            build,
                            first.configuration(),
                            first.contentKey(),
                            first.decodedBytes() + 1,
                            first.candidates(),
                            first.budgets());
            assertEquals(
                    FileLookupException.Code.INVALID_PLAN,
                    assertThrows(
                                    FileLookupException.class,
                                    () ->
                                            gateway.validateSelection(
                                                    new RootSelectionPlan(
                                                            changed,
                                                            changed.candidates().getFirst()),
                                                    CancellationToken.none()))
                            .code());
            for (int i = 0; i < 16; i++)
                gateway.prepareRoot(
                        build,
                        Optional.of(first.configuration()),
                        LookupBudgets.defaults(),
                        CancellationToken.none());
            int before = fixture.requests.size();
            assertEquals(
                    FileLookupException.Code.INVALID_PLAN,
                    assertThrows(
                                    FileLookupException.class,
                                    () ->
                                            gateway.validateSelection(
                                                    new RootSelectionPlan(
                                                            first, first.candidates().getFirst()),
                                                    CancellationToken.none()))
                            .code());
            assertEquals(before, fixture.requests.size());
        }
    }

    @Test
    void
            selectedMetadataSkipsAnOversizedSpecificationChunkInsteadOfLoadingTheWholeEncodingManifest()
                    throws Exception {
        var fixture = new RootLookupFixture(false, false, false, false, true);
        var build = fixture.build("us.cdn.blizzard.com", "1.0");
        var b = LookupBudgets.defaults();
        var smallDecodedWork =
                new LookupBudgets(
                        b.maximumConfigurationBytes(),
                        b.maximumMetadataWireBytes(),
                        b.maximumMetadataRequests(),
                        4096,
                        b.maximumIndexBytes(),
                        b.maximumPageBytes(),
                        b.maximumCandidates(),
                        b.maximumRootEncodedBytes(),
                        b.maximumRootDecodedBytes(),
                        b.maximumHeaderBytes(),
                        b.maximumChunks(),
                        b.maximumEncodedChunkBytes(),
                        b.maximumDecodedChunkBytes(),
                        b.maximumDecodedWorkBytes(),
                        b.maximumSelectedIds(),
                        b.maximumMatches(),
                        b.maximumRootRecords());
        try (var store = new FileObjectStore(directory);
                var gateway =
                        new OfficialFileLookupGateway(
                                store,
                                fixture,
                                Clock.fixed(RootLookupFixture.NOW, ZoneOffset.UTC))) {
            var plan =
                    gateway.prepareRoot(
                            build, Optional.empty(), smallDecodedWork, CancellationToken.none());
            assertEquals(fixture.rootCKey, plan.contentKey());
            byte[] encoded = fixture.objects.get(fixture.encodingEKey);
            var table = java.nio.ByteBuffer.wrap(encoded);
            int headerBytes = table.getInt(4);
            long specificationStart = (long) headerBytes + table.getInt(12);
            long specificationEnd = specificationStart + table.getInt(36);
            assertTrue(table.getInt(40) > smallDecodedWork.maximumDecodedChunkBytes());
            assertTrue(
                    fixture.requests.stream()
                            .filter(request -> request.encodedKey().equals(fixture.encodingEKey))
                            .noneMatch(
                                    request ->
                                            request.start() < specificationEnd
                                                    && request.start() + request.count()
                                                            > specificationStart));
            assertTrue(
                    fixture.requests.stream().mapToLong(request -> request.count()).sum() < 1024);
        }
    }
}
