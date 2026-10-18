package io.vibeminer.application.inventory;

import static io.vibeminer.application.inventory.ManifestReferenceResolver.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ManifestReferenceResolverTest {
  private final ManifestReferenceResolver resolver = new ManifestReferenceResolver();
  private static final String C = "1".repeat(32), D = "2".repeat(32);
  private static final String A = "a".repeat(32), B = "b".repeat(32);

  private static ManifestReferenceResolver.Candidate candidate(String key, long bytes) {
    return new ManifestReferenceResolver.Candidate(key, OptionalLong.of(bytes));
  }

  private static ManifestReferenceResolver.Content content(
      String key, ManifestReferenceResolver.Candidate... candidates) {
    return new ManifestReferenceResolver.Content(key, OptionalLong.of(100), List.of(candidates));
  }

  private static ManifestReferenceResolver.Facts facts(
      ManifestReferenceResolver.Content... contents) {
    return new ManifestReferenceResolver.Facts(true, List.of(contents));
  }

  @Test
  void rootUsesFirstCandidateAndInstallChecksDecodedNotEncodedSize() {
    var facts = facts(content(C, candidate(A, 40), candidate(B, 60)));
    assertEquals(
        A,
        resolver
            .content(C, OptionalLong.empty(), facts, Optional.empty())
            .encodedKey()
            .orElseThrow());
    assertEquals(
        RESOLVED, resolver.content(C, OptionalLong.of(100), facts, Optional.empty()).outcome());
    assertEquals(
        SIZE_CONFLICT, resolver.content(C, OptionalLong.of(40), facts, Optional.empty()).outcome());
  }

  @Test
  void canonicalMustBeCurrentCandidateAndDoesNotReplaceAdvertisedEncoding() {
    var facts = facts(content(C, candidate(A, 40), candidate(B, 60)));
    assertEquals(
        B,
        resolver
            .content(C, OptionalLong.empty(), facts, Optional.of(B))
            .encodedKey()
            .orElseThrow());
    assertEquals(
        CANONICAL_REPRESENTATION_CONFLICT,
        resolver.encoded(B, 60, facts, Optional.of(A)).outcome());
    assertEquals(
        CANONICAL_REPRESENTATION_CONFLICT,
        resolver
            .content(C, OptionalLong.empty(), facts(content(C, candidate(B, 60))), Optional.of(A))
            .outcome());
  }

  @Test
  void incompleteFactsNeverMeanMissingEvenWhenASelectedMatchExists() {
    var partial = new ManifestReferenceResolver.Facts(false, List.of(content(C, candidate(A, 40))));
    assertEquals(
        INCOMPLETE_ENCODING_FACTS, resolver.encoded(A, 40, partial, Optional.empty()).outcome());
    assertEquals(
        INCOMPLETE_ENCODING_FACTS,
        resolver.content(C, OptionalLong.empty(), partial, Optional.empty()).outcome());
    assertEquals(MISSING_MAPPING, resolver.encoded(A, 40, facts(), Optional.empty()).outcome());
  }

  @Test
  void dsPrefixCannotChooseBetweenEncodingsByMatchingSize() {
    String second = A.substring(0, 18) + "b".repeat(14);
    var collision = facts(content(C, candidate(A, 40), candidate(second, 60)));
    assertEquals(
        AMBIGUOUS_MAPPING,
        resolver.encoded(A.substring(0, 18), 40, collision, Optional.empty()).outcome());
    assertEquals(RESOLVED, resolver.encoded(A, 40, collision, Optional.empty()).outcome());
  }

  @Test
  void prefixCollisionRemainsAmbiguousEvenWhenEarlierRepeatedSizeFactsConflict() {
    String second = A.substring(0, 18) + "b".repeat(14);
    var collision = facts(content(C, candidate(A, 40), candidate(A, 41), candidate(second, 60)));
    assertEquals(
        AMBIGUOUS_MAPPING,
        resolver.encoded(A.substring(0, 18), 40, collision, Optional.empty()).outcome());
  }

  @Test
  void reverseMappingToMultipleContentKeysIsAmbiguous() {
    assertEquals(
        AMBIGUOUS_MAPPING,
        resolver
            .encoded(
                A,
                40,
                facts(content(C, candidate(A, 40)), content(D, candidate(A, 40))),
                Optional.empty())
            .outcome());
  }

  @Test
  void repeatedMembershipDoesNotInventExtraObjectsButInconsistentFactsFail() {
    assertEquals(
        RESOLVED,
        resolver
            .encoded(A, 40, facts(content(C, candidate(A, 40), candidate(A, 40))), Optional.empty())
            .outcome());
    assertEquals(
        SIZE_CONFLICT,
        resolver
            .encoded(A, 40, facts(content(C, candidate(A, 40), candidate(A, 41))), Optional.empty())
            .outcome());
    assertEquals(
        SIZE_CONFLICT,
        resolver
            .content(
                C,
                OptionalLong.empty(),
                facts(content(C, candidate(A, 40), candidate(A, 41))),
                Optional.empty())
            .outcome());
  }

  @Test
  void declaredSizeDoesNotFillUnknownEncodingIndexFacts() {
    var unknown = new ManifestReferenceResolver.Candidate(A, OptionalLong.empty());
    assertEquals(
        UNKNOWN_SIZE,
        resolver.encoded(A, 40, facts(content(C, unknown)), Optional.empty()).outcome());
    assertEquals(
        UNKNOWN_SIZE,
        resolver
            .content(C, OptionalLong.empty(), facts(content(C, unknown)), Optional.empty())
            .outcome());
    var unknownDecoded =
        new ManifestReferenceResolver.Content(C, OptionalLong.empty(), List.of(candidate(A, 40)));
    assertEquals(
        UNKNOWN_SIZE,
        resolver
            .content(C, OptionalLong.of(100), facts(unknownDecoded), Optional.empty())
            .outcome());
  }

  @Test
  void encodedLengthsAreCheckedAndPrefixIsNeverPadded() {
    var facts = facts(content(C, candidate(A, 40)));
    assertEquals(SIZE_CONFLICT, resolver.encoded(A, 41, facts, Optional.empty()).outcome());
    assertEquals(
        A,
        resolver
            .encoded(A.substring(0, 18), 40, facts, Optional.empty())
            .encodedKey()
            .orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () -> resolver.encoded("a".repeat(20), 40, facts, Optional.empty()));
  }

  @Test
  void factsAreBoundedAndDefensivelyCopied() {
    var values = new java.util.ArrayList<ManifestReferenceResolver.Content>();
    var input = new ManifestReferenceResolver.Facts(true, values);
    values.add(content(C, candidate(A, 40)));
    assertTrue(input.contents().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ManifestReferenceResolver.Facts(
                true, java.util.Collections.nCopies(257, content(C, candidate(A, 40)))));
  }
}
