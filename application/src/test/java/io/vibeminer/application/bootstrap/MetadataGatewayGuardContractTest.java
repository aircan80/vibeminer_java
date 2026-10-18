package io.vibeminer.application.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.content.MetadataPublicationObserver;
import org.junit.jupiter.api.Test;

class MetadataGatewayGuardContractTest {
  @Test
  void legacyGatewayCannotSilentlyIgnorePublicationOrReplayObserver() {
    var gateway = DefaultBootstrapServiceTest.NO_GATEWAY;
    var token = CancellationToken.none();
    var observer = MetadataPublicationObserver.none();
    assertEquals(
        BootstrapException.Reason.UNSUPPORTED,
        assertThrows(BootstrapException.class, () -> gateway.acquireInstall(null, observer, token))
            .reason());
    assertEquals(
        BootstrapException.Reason.UNSUPPORTED,
        assertThrows(
                BootstrapException.class,
                () -> gateway.inspectRetained(null, null, observer, token))
            .reason());
  }
}
