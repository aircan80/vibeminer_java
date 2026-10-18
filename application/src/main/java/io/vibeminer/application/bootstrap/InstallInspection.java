package io.vibeminer.application.bootstrap;

import io.vibeminer.application.content.MetadataBuildAssociation;
import io.vibeminer.domain.SourceVerificationEvidence;
import io.vibeminer.domain.StoredObject;
import io.vibeminer.domain.content.MetadataStructureAttestation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Verified retained install metadata; associated results separate declared B from actual A. */
public record InstallInspection(
    InstallPlan plan,
    StoredObject object,
    SourceVerificationEvidence verification,
    List<Entry> entries,
    List<Tag> tags,
    Optional<MetadataBuildAssociation> association) {
  public record Tag(String name, int type) {
    public Tag {
      Objects.requireNonNull(name);
    }
  }

  public record Entry(String name, String contentKey, long decodedBytes, List<Tag> tags) {
    public Entry {
      Objects.requireNonNull(name);
      Objects.requireNonNull(contentKey);
      tags = List.copyOf(tags);
    }
  }

  public InstallInspection(
      InstallPlan plan,
      StoredObject object,
      SourceVerificationEvidence verification,
      List<Entry> entries,
      List<Tag> tags) {
    this(plan, object, verification, entries, tags, Optional.empty());
  }

  public InstallInspection {
    Objects.requireNonNull(plan);
    Objects.requireNonNull(object);
    Objects.requireNonNull(verification);
    Objects.requireNonNull(association);
    entries = List.copyOf(entries);
    tags = List.copyOf(tags);
    if (association.isPresent()) {
      var value = association.get();
      var declared = value.declaration();
      var config = declared.configuration();
      var document = plan.configuration();
      var actual = value.actualRepresentation().originalAlias();
      if (declared.kind() != MetadataStructureAttestation.Kind.INSTALL
          || !declared.build().snapshotId().equals(plan.build().snapshotId())
          || !declared.slot().contentKey().equals(plan.contentKey())
          || !declared.preferredEncodingKey().orElse("").equals(plan.encodingKey())
          || declared.declaredEncodedBytes().orElse(-1) != plan.encodedBytes()
          || declared.declaredDecodedBytes().orElse(-1) != plan.decodedBytes()
          || !config.object().digest().value().equals(document.sha256())
          || config.object().length() != document.bytes().length
          || !config.evidence().sourceUrl().equals(document.sourceUrl())
          || !config.evidence().verifiedAt().equals(document.capturedAt())
          || !actual.object().equals(object)
          || !actual.evidence().equals(verification))
        throw new IllegalArgumentException(
            "Associated install result changed declaration or actual source receipt");
    }
  }
}
