package io.vibeminer.domain.content;

import static org.junit.jupiter.api.Assertions.*;

import io.vibeminer.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CompleteManifestMetadataProofTest {
  @Test
  void completeManifestKindsRequireTheirOwnSourceAndExplicitCapabilities() {
    var namespaces =
        Map.of(
            "ENCODING",
            "tact.encoding.v1",
            "DOWNLOAD",
            "tact.download.v1v2v3",
            "DOWNLOAD_SIZE",
            "tact.download-size.v1");
    for (var entry : namespaces.entrySet()) {
      var kind = MetadataStructureAttestation.Kind.valueOf(entry.getKey());
      var c = "a".repeat(32);
      var e = "b".repeat(32);
      var source = new ResolvedSourceIdentity(entry.getValue(), c + ";" + e + ";100;80", "full-v1");
      var alias =
          new VerifiedSourceAlias(
              source,
              new StoredObject(new ObjectDigest("c".repeat(64)), 80),
              new SourceVerificationEvidence(
                  "full-v1", "https://example.test/data", Instant.EPOCH));
      var historical =
          new MetadataContentProof(
              alias, e, c, OptionalLong.of(100), Optional.empty(), Optional.empty());
      assertTrue(historical.decodedAttestation().isEmpty());
      var decoded = Optional.of(new DecodedContentAttestation(c, 100, "full-md5-v1"));
      var full =
          historical.withAttestations(
              decoded, Optional.of(new MetadataStructureAttestation(kind, "parser-v1")));
      assertEquals(alias, full.originalAlias());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              historical.withAttestations(
                  decoded,
                  Optional.of(
                      new MetadataStructureAttestation(
                          MetadataStructureAttestation.Kind.ROOT, "parser-v1"))));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new MetadataContentProof(
                  alias, e, c, OptionalLong.of(101), Optional.empty(), Optional.empty()));
    }
  }
}
