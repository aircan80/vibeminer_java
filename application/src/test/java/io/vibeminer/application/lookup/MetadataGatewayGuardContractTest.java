package io.vibeminer.application.lookup;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.content.MetadataPublicationObserver;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetadataGatewayGuardContractTest {
  @Test
  void legacyGatewayCannotSilentlyIgnorePublicationOrReplayObserver() {
    var gateway = new DefaultFileLookupServiceTest.Gateway();
    var token = CancellationToken.none();
    var observer = MetadataPublicationObserver.none();
    assertEquals(
        FileLookupException.Code.UNSUPPORTED,
        assertThrows(FileLookupException.class, () -> gateway.acquireRoot(null, observer, token))
            .code());
    assertEquals(
        FileLookupException.Code.UNSUPPORTED,
        assertThrows(
                FileLookupException.class,
                () ->
                    gateway.lookupRetained(
                        null, null, null, Set.of(), LookupBudgets.defaults(), observer, token))
            .code());
    assertEquals(0, gateway.acquisitions);
    assertEquals(0, gateway.lookups);
  }
}
