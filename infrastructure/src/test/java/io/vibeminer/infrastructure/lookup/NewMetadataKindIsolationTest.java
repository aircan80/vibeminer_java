package io.vibeminer.infrastructure.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.*;
import io.vibeminer.application.content.*;
import io.vibeminer.application.lookup.LookupBudgets;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewMetadataKindIsolationTest {
  @TempDir Path directory;

  @Test
  void oldLocalGatewaysRejectNewKindsBeforeReadingAnyMetadataOrIssuingRequests() throws Exception {
    try (var fixture = new MetadataRepairPlanningTest.Fixture(directory);
        var reuse = new OfficialMetadataReuseGateway(fixture.store);
        var local = new OfficialMetadataLocalVerificationGateway(fixture.store)) {
      var before = fixture.client.requests.size();
      for (var kind :
          List.of(
              MetadataStructureAttestation.Kind.ENCODING,
              MetadataStructureAttestation.Kind.DOWNLOAD,
              MetadataStructureAttestation.Kind.DOWNLOAD_SIZE)) {
        var failed =
            assertThrows(
                ContentClaimException.class,
                () ->
                    reuse.readDeclaration(
                        fixture.install.build(),
                        fixture.installConfiguration,
                        kind,
                        LookupBudgets.defaults(),
                        CancellationToken.none()));
        assertEquals(ContentClaimException.Code.UNSUPPORTED_REUSE, failed.code());
        var request =
            new MetadataContentRequest(
                fixture.install.build(),
                fixture.installConfiguration,
                kind,
                new ContentSlotKey("wow", fixture.install.contentKey()),
                fixture.install.encodingKey(),
                fixture.install.encodedBytes(),
                OptionalLong.of(fixture.install.decodedBytes()));
        var verify =
            assertThrows(
                ContentClaimException.class,
                () ->
                    local.verifyOriginal(
                        request, null, LookupBudgets.defaults(), CancellationToken.none()));
        assertEquals(ContentClaimException.Code.UNSUPPORTED_REUSE, verify.code());
      }
      assertEquals(before, fixture.client.requests.size());
    }
  }
}
