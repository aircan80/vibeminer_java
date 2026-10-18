package io.vibeminer.application.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Pure bounded reconciliation of one provisional manifest reference, never inventory authority. */
public final class ManifestReferenceResolver {
  public enum Outcome {
    RESOLVED,
    INCOMPLETE_ENCODING_FACTS,
    MISSING_MAPPING,
    AMBIGUOUS_MAPPING,
    UNKNOWN_SIZE,
    SIZE_CONFLICT,
    CANONICAL_REPRESENTATION_CONFLICT
  }

  public record Candidate(String encodedKey, OptionalLong encodedBytes) {
    public Candidate {
      key(encodedKey);
      size(encodedBytes);
    }
  }

  public record Content(String contentKey, OptionalLong decodedBytes, List<Candidate> candidates) {
    public Content {
      key(contentKey);
      size(decodedBytes);
      candidates = List.copyOf(candidates);
      if (candidates.size() > 255) throw new IllegalArgumentException("Too many EN candidates");
    }
  }

  /** Caller must bound database enumeration; false means truncated/unverified, never absent. */
  public record Facts(boolean exhaustive, List<Content> contents) {
    public Facts {
      contents = List.copyOf(contents);
      if (contents.size() > 256) throw new IllegalArgumentException("Unbounded resolution facts");
    }
  }

  /** Identity and lengths only: no source verification, locator or publication permission. */
  public record Resolution(
      Outcome outcome,
      Optional<String> contentKey,
      Optional<String> encodedKey,
      OptionalLong decodedBytes,
      OptionalLong encodedBytes) {
    public Resolution {
      Objects.requireNonNull(outcome);
      Objects.requireNonNull(contentKey);
      Objects.requireNonNull(encodedKey);
      size(decodedBytes);
      size(encodedBytes);
    }
  }

  /** ROOT has no size; IN's declared size is decoded. Caller preserves every origin row. */
  public Resolution content(
      String contentKey,
      OptionalLong declaredDecodedBytes,
      Facts facts,
      Optional<String> canonicalEncodedKey) {
    key(contentKey);
    size(declaredDecodedBytes);
    canonical(canonicalEncodedKey);
    if (!facts.exhaustive()) return failure(Outcome.INCOMPLETE_ENCODING_FACTS);
    var matches = facts.contents().stream().filter(c -> c.contentKey().equals(contentKey)).toList();
    if (matches.isEmpty()) return failure(Outcome.MISSING_MAPPING);
    if (matches.size() != 1) return failure(Outcome.AMBIGUOUS_MAPPING);
    var content = matches.getFirst();
    if (conflict(declaredDecodedBytes, content.decodedBytes()))
      return failure(Outcome.SIZE_CONFLICT);
    if (content.decodedBytes().isEmpty()) return failure(Outcome.UNKNOWN_SIZE);
    if (content.candidates().isEmpty()) return failure(Outcome.MISSING_MAPPING);
    Candidate chosen = null;
    if (canonicalEncodedKey.isPresent()) {
      for (var candidate : content.candidates()) {
        if (candidate.encodedKey().equals(canonicalEncodedKey.get())) {
          if (chosen != null && !chosen.equals(candidate)) return failure(Outcome.SIZE_CONFLICT);
          chosen = candidate;
        }
      }
      if (chosen == null) return failure(Outcome.CANONICAL_REPRESENTATION_CONFLICT);
    } else {
      // EN order is retained. Unknown first-candidate cost is not permission to skip alternatives.
      chosen = content.candidates().getFirst();
      for (var candidate : content.candidates()) {
        if (candidate.encodedKey().equals(chosen.encodedKey()) && !candidate.equals(chosen))
          return failure(Outcome.SIZE_CONFLICT);
      }
    }
    if (chosen.encodedBytes().isEmpty()) return failure(Outcome.UNKNOWN_SIZE);
    return resolved(content, chosen);
  }

  /** DL/DS full key or DS 9-byte prefix. Prefix uniqueness is checked before size matching. */
  public Resolution encoded(
      String keyReference,
      long declaredEncodedBytes,
      Facts facts,
      Optional<String> canonicalEncodedKey) {
    if (keyReference == null || !keyReference.matches("(?:[0-9a-f]{18}|[0-9a-f]{32})"))
      throw new IllegalArgumentException("Expected full EKey or nine-byte prefix");
    if (declaredEncodedBytes < 0) throw new IllegalArgumentException("Negative declared size");
    canonical(canonicalEncodedKey);
    if (!facts.exhaustive()) return failure(Outcome.INCOMPLETE_ENCODING_FACTS);
    String exactKey = null;
    Content matchedContent = null;
    Candidate matchedCandidate = null;
    boolean conflictingSizes = false;
    for (var content : facts.contents()) {
      for (var candidate : content.candidates()) {
        if (!candidate.encodedKey().startsWith(keyReference)) continue;
        if (exactKey != null && !exactKey.equals(candidate.encodedKey()))
          return failure(Outcome.AMBIGUOUS_MAPPING);
        if (matchedContent != null && !matchedContent.contentKey().equals(content.contentKey()))
          return failure(Outcome.AMBIGUOUS_MAPPING);
        if (matchedContent != null
            && (!matchedContent.decodedBytes().equals(content.decodedBytes())
                || !matchedCandidate.encodedBytes().equals(candidate.encodedBytes())))
          conflictingSizes = true;
        exactKey = candidate.encodedKey();
        matchedContent = content;
        matchedCandidate = candidate;
      }
    }
    if (matchedContent == null) return failure(Outcome.MISSING_MAPPING);
    if (conflictingSizes) return failure(Outcome.SIZE_CONFLICT);
    if (matchedCandidate.encodedBytes().isPresent()
        && matchedCandidate.encodedBytes().getAsLong() != declaredEncodedBytes)
      return failure(Outcome.SIZE_CONFLICT);
    if (canonicalEncodedKey.isPresent() && !canonicalEncodedKey.get().equals(exactKey))
      return failure(Outcome.CANONICAL_REPRESENTATION_CONFLICT);
    if (matchedContent.decodedBytes().isEmpty() || matchedCandidate.encodedBytes().isEmpty())
      return failure(Outcome.UNKNOWN_SIZE);
    return resolved(matchedContent, matchedCandidate);
  }

  private static Resolution resolved(Content content, Candidate candidate) {
    return new Resolution(
        Outcome.RESOLVED,
        Optional.of(content.contentKey()),
        Optional.of(candidate.encodedKey()),
        content.decodedBytes(),
        candidate.encodedBytes());
  }

  private static Resolution failure(Outcome outcome) {
    return new Resolution(
        outcome, Optional.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
  }

  private static boolean conflict(OptionalLong left, OptionalLong right) {
    return left.isPresent() && right.isPresent() && left.getAsLong() != right.getAsLong();
  }

  private static void canonical(Optional<String> canonical) {
    Objects.requireNonNull(canonical);
    canonical.ifPresent(ManifestReferenceResolver::key);
  }

  private static void key(String key) {
    if (key == null || !key.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Expected canonical full key");
  }

  private static void size(OptionalLong size) {
    Objects.requireNonNull(size);
    if (size.isPresent() && size.getAsLong() < 0)
      throw new IllegalArgumentException("Negative size");
  }
}
