package io.vibeminer.application.buildopening;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.domain.StoredObject;
import java.util.*;

/**
 * Gateway-owned committed complete metadata closure, not a sealed payload inventory. Opaque proof
 * identities name future concrete verifier/catalog facts; construction does not verify source
 * bytes. No ROOT/INSTALL alias is reinterpreted as EN, DL or an index proof by these values.
 */
public record BuildMetadataAdmission(
    BuildLibraryReference reference,
    String locale,
    String scopeRevision,
    List<Requirement> required,
    DownloadDeclaration download,
    SizeDeclaration size,
    LocatorRequirements locators,
    List<VerifiedRequirement> verified) {
  public static final int MAXIMUM_REQUIREMENTS = 1024;

  public enum Role {
    BUILD_CONFIG,
    CDN_CONFIG,
    ENCODING,
    ROOT,
    INSTALL,
    DOWNLOAD,
    SIZE,
    LOCATOR_INDEX
  }

  public enum DownloadApplicability {
    REQUIRED,
    AUTHENTICATED_NOT_PUBLISHED
  }

  public enum SizeApplicability {
    REQUIRED,
    AUTHENTICATED_NOT_PUBLISHED
  }

  public record Requirement(Role role, String identity) {
    public Requirement {
      Objects.requireNonNull(role);
      identity = text(identity, 256);
    }
  }

  public record ProofIdentity(Role role, String commitment) {
    public ProofIdentity {
      Objects.requireNonNull(role);
      commitment = digest(commitment);
    }
  }

  public record VerifiedRequirement(
      Requirement requirement, StoredObject object, ProofIdentity proof) {
    public VerifiedRequirement {
      Objects.requireNonNull(requirement);
      Objects.requireNonNull(object);
      Objects.requireNonNull(proof);
      if (object.length() < 1 || proof.role() != requirement.role())
        throw new IllegalArgumentException("Exact role-specific verified object identity required");
    }
  }

  /** NOT_PUBLISHED requires an authenticated config declaration commitment, never a 404. */
  public record DownloadDeclaration(
      DownloadApplicability applicability, String declarationProofIdentity) {
    public DownloadDeclaration {
      Objects.requireNonNull(applicability);
      declarationProofIdentity = digest(declarationProofIdentity);
    }
  }

  /** DS is required when its complete source is published; entry keys may be prefixes. */
  public record SizeDeclaration(SizeApplicability applicability, String declarationProofIdentity) {
    public SizeDeclaration {
      Objects.requireNonNull(applicability);
      declarationProofIdentity = digest(declarationProofIdentity);
    }
  }

  /** An empty list is an explicit closure-plan fact, never an implicit unexamined default. */
  public record LocatorRequirements(List<Requirement> required, String closureProofIdentity) {
    public LocatorRequirements {
      required = List.copyOf(required);
      closureProofIdentity = digest(closureProofIdentity);
      if (required.size() > MAXIMUM_REQUIREMENTS
          || new HashSet<>(required).size() != required.size()
          || required.stream().anyMatch(value -> value.role() != Role.LOCATOR_INDEX))
        throw new IllegalArgumentException(
            "Exact required locator closure and proof identity required");
    }
  }

  public BuildMetadataAdmission {
    Objects.requireNonNull(reference);
    locale = locale(locale);
    scopeRevision = text(scopeRevision, 128);
    required = List.copyOf(required);
    verified = List.copyOf(verified);
    Objects.requireNonNull(download);
    Objects.requireNonNull(size);
    Objects.requireNonNull(locators);
    var requirements = new HashSet<>(required);
    if (required.size() > MAXIMUM_REQUIREMENTS
        || requirements.size() != required.size()
        || verified.size() != required.size())
      throw new IllegalArgumentException("Bounded one-to-one complete metadata closure required");
    for (var role :
        List.of(Role.BUILD_CONFIG, Role.CDN_CONFIG, Role.ENCODING, Role.ROOT, Role.INSTALL))
      if (required.stream().filter(value -> value.role() == role).count() != 1)
        throw new IllegalArgumentException(
            "Required base metadata role is missing or ambiguous: " + role);
    long downloads = required.stream().filter(value -> value.role() == Role.DOWNLOAD).count();
    if (downloads != (download.applicability() == DownloadApplicability.REQUIRED ? 1 : 0))
      throw new IllegalArgumentException("Download applicability differs from required closure");
    long sizes = required.stream().filter(value -> value.role() == Role.SIZE).count();
    if (sizes != (size.applicability() == SizeApplicability.REQUIRED ? 1 : 0))
      throw new IllegalArgumentException(
          "Size-manifest applicability differs from required closure");
    var indexes =
        required.stream()
            .filter(value -> value.role() == Role.LOCATOR_INDEX)
            .collect(java.util.stream.Collectors.toSet());
    if (!indexes.equals(new HashSet<>(locators.required())))
      throw new IllegalArgumentException("Required locator closure differs");
    var facts = new HashSet<Requirement>();
    for (var fact : verified)
      if (!requirements.contains(fact.requirement()) || !facts.add(fact.requirement()))
        throw new IllegalArgumentException(
            "Every exact metadata requirement needs one matching verified fact");
  }

  static String locale(String value) {
    if (value == null || !value.matches("[a-z]{2}[A-Z]{2}"))
      throw new IllegalArgumentException("Research locale required");
    return value;
  }

  private static String text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.chars().anyMatch(Character::isISOControl)
        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
      throw new IllegalArgumentException("Bounded exact metadata text required");
    return value;
  }

  private static String digest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Opaque SHA256 proof commitment required");
    return value;
  }
}
