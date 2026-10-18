package io.vibeminer.domain.selectedfile;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectedFileAdversarialContractsTest {
  private FileVariantAssociation variant(
      long locale, long content, long first, long second, int third, Optional<String> name) {
    var value = SelectedFileContractsTest.variant();
    return new FileVariantAssociation(
        value.build(),
        value.rootSource(),
        value.fileDataId(),
        value.contentKey(),
        locale,
        content,
        first,
        second,
        third,
        name);
  }

  private String role(FileVariantAssociation variant) {
    return new SelectedFileDescriptor(
            variant, SelectedFileContractsTest.EKEY, ContentRepresentation.VERIFIER_SEMANTICS)
        .role();
  }

  @Test
  void unsignedRootFactsRejectOverflowWithoutRestrictingOpaqueValidBits() {
    assertDoesNotThrow(
        () -> variant(0xffffffffL, 0xffffffffL, 0xffffffffL, 0xffffffffL, 255, Optional.empty()));
    for (long value : new long[] {-1, 0x100000000L, Long.MAX_VALUE}) {
      assertThrows(
          IllegalArgumentException.class, () -> variant(value, 0, 0, 0, 0, Optional.empty()));
      assertThrows(
          IllegalArgumentException.class, () -> variant(0, value, 0, 0, 0, Optional.empty()));
      assertThrows(
          IllegalArgumentException.class, () -> variant(0, 0, value, 0, 0, Optional.empty()));
      assertThrows(
          IllegalArgumentException.class, () -> variant(0, 0, 0, value, 0, Optional.empty()));
    }
    for (int value : new int[] {-1, 256, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> variant(0, 0, 0, 0, value, Optional.empty()));
  }

  @Test
  void absentNameAndPresentZeroNameHaveDistinctCanonicalRolesAndEachRawFactParticipates() {
    var roles = new HashSet<String>();
    roles.add(role(variant(0, 0, 0, 0, 0, Optional.empty())));
    roles.add(role(variant(0, 0, 0, 0, 0, Optional.of("0".repeat(16)))));
    roles.add(role(variant(1, 0, 0, 0, 0, Optional.empty())));
    roles.add(role(variant(0, 1, 0, 0, 0, Optional.empty())));
    roles.add(role(variant(0, 0, 1, 0, 0, Optional.empty())));
    roles.add(role(variant(0, 0, 0, 1, 0, Optional.empty())));
    roles.add(role(variant(0, 0, 0, 0, 1, Optional.empty())));
    assertEquals(7, roles.size());
    for (String role : roles)
      assertEquals(32, Base64.getUrlDecoder().decode(role.substring(4)).length);
  }

  @Test
  void canonicalSourceComponentsAreDelimitedAndKeyCaseDoesNotSplitIdentity() {
    var base = SelectedFileContractsTest.variant();
    var roles = new HashSet<String>();
    for (var source :
        List.of(
            new ResolvedSourceIdentity("ab", "c", "d"),
            new ResolvedSourceIdentity("a", "bc", "d"),
            new ResolvedSourceIdentity("a", "b", "cd"))) {
      var variant =
          new FileVariantAssociation(
              base.build(), source, 42, base.contentKey(), 0, 0, 0, 0, 0, Optional.empty());
      roles.add(role(variant));
    }
    assertEquals(3, roles.size());
    assertEquals(
        role(base),
        new SelectedFileDescriptor(
                base,
                SelectedFileContractsTest.EKEY.toUpperCase(Locale.ROOT),
                ContentRepresentation.VERIFIER_SEMANTICS)
            .role());
  }

  @Test
  void encodedScopeDoesNotAuthorizeDecodedIdentityOrRelabelVerifierEvidence() {
    var value = SelectedFileContractsTest.representation();
    var wrong =
        new ResolvedSourceIdentity(
            ContentRepresentation.SOURCE_NAMESPACE,
            SelectedFileContractsTest.EKEY,
            "decoded-content-v1");
    var alias =
        new VerifiedSourceAlias(
            wrong,
            value.encoded().object(),
            new SourceVerificationEvidence(
                wrong.verificationSemantics(),
                value.sourceReceipt().actualResourceUrl(),
                Instant.EPOCH));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ContentRepresentation(
                value.slot(),
                alias,
                EncodedScope.HEADER_AND_ALL_ENCODED_CHUNKS,
                value.sourceReceipt()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VerifiedSourceAlias(
                value.encoded().source(),
                value.encoded().object(),
                new SourceVerificationEvidence(
                    "decoded-content-v1",
                    value.sourceReceipt().actualResourceUrl(),
                    Instant.EPOCH)));
  }

  @Test
  void archiveReceiptPinsIndexAndChecksOverflowSafeChildExtent() {
    var proof =
        new ArchiveMappingProof(
            "1".repeat(32), "2".repeat(64), "3".repeat(64), "4".repeat(64), "fixture-v1");
    var valid =
        new EncodedSourceReceipt(
            EncodedSourceReceipt.Kind.ARCHIVE,
            "https://example.invalid/archive",
            "1".repeat(32),
            Long.MAX_VALUE,
            Long.MAX_VALUE - 10,
            10,
            Instant.EPOCH,
            1,
            10,
            "5".repeat(64),
            Optional.of(proof));
    assertEquals(Long.MAX_VALUE - 10, valid.childOffset());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncodedSourceReceipt(
                EncodedSourceReceipt.Kind.ARCHIVE,
                "https://example.invalid/archive",
                "1".repeat(32),
                Long.MAX_VALUE,
                Long.MAX_VALUE - 9,
                10,
                Instant.EPOCH,
                1,
                10,
                "5".repeat(64),
                Optional.of(proof)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EncodedSourceReceipt(
                EncodedSourceReceipt.Kind.ARCHIVE,
                "https://example.invalid/archive",
                "6".repeat(32),
                100,
                10,
                10,
                Instant.EPOCH,
                1,
                10,
                "5".repeat(64),
                Optional.of(proof)));
  }
}
