package io.vibeminer.infrastructure.buildopening;

import io.vibeminer.application.buildlibrary.BuildLibraryReference;
import io.vibeminer.application.buildopening.BuildMetadataAdmission;
import io.vibeminer.application.buildopening.BuildMetadataAdmission.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import io.vibeminer.infrastructure.content.CanonicalContentRecords;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Concrete source facts, not a capability: the repository rechecks every ledger fact in its
 * transaction.
 */
public record BuildMetadataAdmissionFacts(
    BuildLibraryReference reference,
    String locale,
    String scopeRevision,
    BuildMetadataDeclarationPlan declaration,
    List<ConfigurationFact> configurations,
    List<MetadataFact> metadata) {
  public record ConfigurationFact(Role role, VerifiedSourceAlias originalAlias) {
    public ConfigurationFact {
      Objects.requireNonNull(originalAlias);
      if (role != Role.BUILD_CONFIG && role != Role.CDN_CONFIG
          || !originalAlias.source().namespace().equals("tact.config.raw-md5")
          || !originalAlias.source().verificationSemantics().equals("tact-config-raw-md5-v1"))
        throw new IllegalArgumentException("Exact raw-MD5 configuration role required");
    }
  }

  public record MetadataFact(
      Role role, ContentSlotKey slot, UUID canonicalReservationToken, MetadataContentProof proof) {
    public MetadataFact {
      Objects.requireNonNull(slot);
      Objects.requireNonNull(canonicalReservationToken);
      Objects.requireNonNull(proof);
      if (!slot.namespace().equals("wow")
          || !slot.contentKey().equals(proof.contentKey())
          || proof.decodedAttestation().isEmpty()
          || proof.structuralAttestation().isEmpty()
          || kind(role) != proof.structuralAttestation().get().kind())
        throw new IllegalArgumentException(
            "Exact complete role/slot/decoded/grammar proof required");
    }
  }

  public BuildMetadataAdmissionFacts {
    Objects.requireNonNull(reference);
    Objects.requireNonNull(declaration);
    configurations =
        configurations.stream().sorted(Comparator.comparing(f -> f.role().ordinal())).toList();
    metadata = metadata.stream().sorted(Comparator.comparing(f -> f.role().ordinal())).toList();
    if (locale == null
        || !locale.matches("[a-z]{2}[A-Z]{2}")
        || scopeRevision == null
        || scopeRevision.isBlank()
        || scopeRevision.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(scopeRevision)
        || scopeRevision.chars().anyMatch(Character::isISOControl)
        || !declaration.build().snapshotId().equals(reference.snapshotId())
        || configurations.size() != 2
        || metadata.size() != declaration.requiredSources().size())
      throw new IllegalArgumentException("Exact bounded classified metadata context required");
    var roles = EnumSet.noneOf(Role.class);
    for (var c : configurations) {
      if (!roles.add(c.role())) throw new IllegalArgumentException("Duplicate configuration role");
      var key =
          c.role() == Role.BUILD_CONFIG
              ? reference.key().buildConfig()
              : reference.key().cdnConfig();
      if (!c.originalAlias().source().immutableSourceKey().equals(key)
          || c.role() == Role.BUILD_CONFIG
              && !c.originalAlias().equals(declaration.configuration()))
        throw new IllegalArgumentException("Configuration differs from exact declared identity");
    }
    for (var m : metadata) {
      if (!roles.add(m.role())) throw new IllegalArgumentException("Duplicate metadata role");
      var source = declaration.source(kind(m.role()));
      if (!source.contentKey().equals(m.slot().contentKey())
          || source.decodedBytes().isPresent()
              && source.decodedBytes().getAsLong()
                  != m.proof().decodedAttestation().orElseThrow().actualDecodedBytes())
        throw new IllegalArgumentException("Metadata proof differs from authenticated declaration");
    }
  }

  public BuildMetadataAdmission admission() {
    var required = new ArrayList<Requirement>();
    var verified = new ArrayList<VerifiedRequirement>();
    for (var c : configurations) {
      var req = new Requirement(c.role(), c.originalAlias().source().immutableSourceKey());
      required.add(req);
      verified.add(
          new VerifiedRequirement(
              req,
              c.originalAlias().object(),
              new ProofIdentity(c.role(), hash(configurationBytes(c.originalAlias())))));
    }
    for (var m : metadata) {
      var req = new Requirement(m.role(), m.slot().contentKey());
      required.add(req);
      verified.add(
          new VerifiedRequirement(
              req,
              m.proof().object(),
              new ProofIdentity(m.role(), hash(CanonicalContentRecords.encode(m.proof())))));
    }
    var config = declaration.rawSha256();
    return new BuildMetadataAdmission(
        reference,
        locale,
        scopeRevision,
        required,
        new DownloadDeclaration(
            declaration.downloadPublished()
                ? DownloadApplicability.REQUIRED
                : DownloadApplicability.AUTHENTICATED_NOT_PUBLISHED,
            hash(
                (declaration.classifierRevision()
                        + "|download|"
                        + config
                        + "|"
                        + declaration.downloadPublished())
                    .getBytes(StandardCharsets.UTF_8))),
        new SizeDeclaration(
            declaration.sizePublished()
                ? SizeApplicability.REQUIRED
                : SizeApplicability.AUTHENTICATED_NOT_PUBLISHED,
            hash(
                (declaration.classifierRevision()
                        + "|size|"
                        + config
                        + "|"
                        + declaration.sizePublished())
                    .getBytes(StandardCharsets.UTF_8))),
        new LocatorRequirements(
            List.of(),
            hash(
                (scopeRevision + "|verified-loose-or-retained-metadata|" + config)
                    .getBytes(StandardCharsets.UTF_8))),
        verified);
  }

  static MetadataStructureAttestation.Kind kind(Role role) {
    if (role == null) throw new IllegalArgumentException("Metadata role required");
    return switch (role) {
      case ROOT -> MetadataStructureAttestation.Kind.ROOT;
      case INSTALL -> MetadataStructureAttestation.Kind.INSTALL;
      case ENCODING -> MetadataStructureAttestation.Kind.ENCODING;
      case DOWNLOAD -> MetadataStructureAttestation.Kind.DOWNLOAD;
      case SIZE -> MetadataStructureAttestation.Kind.DOWNLOAD_SIZE;
      default -> throw new IllegalArgumentException("Configuration/index is not a content role");
    };
  }

  static byte[] configurationBytes(VerifiedSourceAlias a) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      for (var text :
          List.of(
              "build-config-proof-v1",
              a.source().namespace(),
              a.source().immutableSourceKey(),
              a.source().verificationSemantics(),
              a.object().digest().value(),
              Long.toString(a.object().length()),
              a.evidence().sourceUrl(),
              a.evidence().verifiedAt().toString())) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text))
          throw new IllegalArgumentException("Configuration provenance must be strict Unicode");
        var raw = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
