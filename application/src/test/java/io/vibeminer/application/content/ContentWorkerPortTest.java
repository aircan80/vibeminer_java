package io.vibeminer.application.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContentWorkerPortTest {
  static class Legacy implements CanonicalContentRepository {
    int calls;

    public Optional<ContentClaim> lookup(ContentSlotKey key, CancellationToken token) {
      calls++;
      return Optional.empty();
    }

    public ContentClaim reserve(
        ContentSlotKey key, String ekey, long length, CancellationToken token) {
      calls++;
      return null;
    }

    public void markPublishing(UUID id, CancellationToken token) {
      calls++;
    }

    public void recordVerifiedCompletion(
        UUID id, CanonicalContentProof proof, CancellationToken token) {
      calls++;
    }

    public ContentClaim completePublication(
        UUID id, CanonicalContentProof proof, CancellationToken token) {
      calls++;
      return null;
    }

    public ContentClaim upgradeProof(
        UUID id, CanonicalContentProof proof, CancellationToken token) {
      calls++;
      return null;
    }

    public void releaseUnpublished(UUID id, CancellationToken token) {
      calls++;
    }

    public void markUncertain(UUID id, CancellationToken token) {
      calls++;
    }
  }

  @Test
  void newPermitDefaultsNeverFallBackToUnfencedTokenMutations() {
    var legacy = new Legacy();
    var permit = new ContentWorkerPermit(UUID.randomUUID(), UUID.randomUUID());
    var token = CancellationToken.none();
    assertEquals(
        ContentClaimException.Code.UNSUPPORTED_REUSE,
        assertThrows(
                ContentClaimException.class,
                () -> legacy.claimWorker(permit.reservationToken(), token))
            .code());
    assertThrows(ContentClaimException.class, () -> legacy.markPublishing(permit, token));
    assertThrows(
        ContentClaimException.class, () -> legacy.recordVerifiedCompletion(permit, null, token));
    assertThrows(
        ContentClaimException.class, () -> legacy.completePublication(permit, null, token));
    assertThrows(ContentClaimException.class, () -> legacy.upgradeProof(permit, null, token));
    assertThrows(
        ContentClaimException.class,
        () -> legacy.finishWorker(permit, ContentWorkerFinishOutcome.UNCERTAIN, token));
    assertEquals(0, legacy.calls);
  }

  @Test
  void permitIsDistinctFromReservationAndRejectsMissingIdentities() {
    var reservation = UUID.randomUUID();
    var first = new ContentWorkerPermit(reservation, UUID.randomUUID());
    var second = new ContentWorkerPermit(reservation, UUID.randomUUID());
    assertNotEquals(first, second);
    assertEquals(first.reservationToken(), second.reservationToken());
    assertThrows(
        NullPointerException.class, () -> new ContentWorkerPermit(null, UUID.randomUUID()));
    assertThrows(NullPointerException.class, () -> new ContentWorkerPermit(reservation, null));
  }

  @Test
  void selectedLegacyAdapterAlsoCannotReceiveUnfencedFallback() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var legacy =
        (io.vibeminer.application.selectedfile.SelectedFileCatalog)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {io.vibeminer.application.selectedfile.SelectedFileCatalog.class},
                (proxy, method, arguments) -> {
                  if (method.isDefault())
                    return java.lang.reflect.InvocationHandler.invokeDefault(
                        proxy, method, arguments);
                  calls.incrementAndGet();
                  return null;
                });
    var permit = new ContentWorkerPermit(UUID.randomUUID(), UUID.randomUUID());
    var token = CancellationToken.none();
    assertEquals(
        io.vibeminer.application.selectedfile.SelectedFileException.Code.UNSUPPORTED,
        assertThrows(
                io.vibeminer.application.selectedfile.SelectedFileException.class,
                () -> legacy.claimWorker(permit.reservationToken(), token))
            .code());
    assertThrows(
        io.vibeminer.application.selectedfile.SelectedFileException.class,
        () -> legacy.markPublishing(permit, token));
    assertThrows(
        io.vibeminer.application.selectedfile.SelectedFileException.class,
        () -> legacy.recordVerifiedCompletion(permit, null, token));
    assertThrows(
        io.vibeminer.application.selectedfile.SelectedFileException.class,
        () -> legacy.completePublication(permit, null, token));
    assertThrows(
        io.vibeminer.application.selectedfile.SelectedFileException.class,
        () -> legacy.finishWorker(permit, ContentWorkerFinishOutcome.UNCERTAIN, token));
    assertEquals(0, calls.get());
  }
}
