package io.vibeminer.infrastructure.buildopening;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.application.buildopening.BuildMetadataAdmission.Role;
import io.vibeminer.domain.*;
import io.vibeminer.domain.content.*;
import io.vibeminer.domain.selectedfile.ContentSlotKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildMetadataAdmissionFactsTest {
  static final String C = "a".repeat(32), E = "b".repeat(32);

  static MetadataContentProof proof(MetadataStructureAttestation.Kind kind) {
    String ns =
        switch (kind) {
          case ROOT -> "wow.root.tsfm.v1v2";
          case INSTALL -> "tact.install.v1";
          case ENCODING -> "tact.encoding.v1";
          case DOWNLOAD -> "tact.download.v1v2v3";
          case DOWNLOAD_SIZE -> "tact.download-size.v1";
        };
    var alias =
        new VerifiedSourceAlias(
            new ResolvedSourceIdentity(ns, C + ";" + E + ";20;18", "fixture-v1"),
            new StoredObject(new ObjectDigest("c".repeat(64)), 18),
            new SourceVerificationEvidence(
                "fixture-v1", "https://example.test/data/" + E, Instant.EPOCH));
    return new MetadataContentProof(
        alias,
        E,
        C,
        OptionalLong.of(20),
        Optional.of(new DecodedContentAttestation(C, 20, "fixture-v1")),
        Optional.of(new MetadataStructureAttestation(kind, "fixture-v1")));
  }

  @Test
  void configurationCannotMasqueradeAsContentRole() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmissionFacts.ConfigurationFact(
                Role.ROOT, proof(MetadataStructureAttestation.Kind.ROOT).originalAlias()));
  }

  @Test
  void metadataRoleMustMatchActualCompleteGrammarAndSlot() {
    var p = proof(MetadataStructureAttestation.Kind.ROOT);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmissionFacts.MetadataFact(
                Role.INSTALL, new ContentSlotKey("wow", C), UUID.randomUUID(), p));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmissionFacts.MetadataFact(
                Role.ROOT, new ContentSlotKey("wow", "d".repeat(32)), UUID.randomUUID(), p));
    var weak =
        new MetadataContentProof(
            p.originalAlias(), E, C, OptionalLong.of(20), Optional.empty(), Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BuildMetadataAdmissionFacts.MetadataFact(
                Role.ROOT, new ContentSlotKey("wow", C), UUID.randomUUID(), weak));
    assertDoesNotThrow(
        () ->
            new BuildMetadataAdmissionFacts.MetadataFact(
                Role.ROOT, new ContentSlotKey("wow", C), UUID.randomUUID(), p));
  }
}
