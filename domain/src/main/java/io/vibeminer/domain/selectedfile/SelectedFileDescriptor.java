package io.vibeminer.domain.selectedfile;

import io.vibeminer.domain.ResolvedSourceIdentity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/** Exact immutable association identity; representation choices never alter an existing role. */
public record SelectedFileDescriptor(
    FileVariantAssociation association, String actualEncodingKey, String encodedVerifierSemantics) {
  public SelectedFileDescriptor {
    Objects.requireNonNull(association);
    actualEncodingKey = SelectedFileValues.key(actualEncodingKey);
    if (!ContentRepresentation.VERIFIER_SEMANTICS.equals(encodedVerifierSemantics))
      throw new IllegalArgumentException("Unsupported encoded verifier semantics");
  }

  /** Full 256-bit digest in a 47-character role, not a truncated content identity. */
  public String role() {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      text(output, "selected-file-descriptor-v1");
      text(output, association.build().snapshotId());
      source(output, association.rootSource());
      output.writeLong(association.fileDataId());
      text(output, association.contentKey());
      output.writeLong(association.localeFlags());
      output.writeLong(association.contentFlags());
      output.writeLong(association.rawFlagsFirst());
      output.writeLong(association.rawFlagsSecond());
      output.writeInt(association.rawFlagsThird());
      output.writeBoolean(association.nameHash().isPresent());
      if (association.nameHash().isPresent()) text(output, association.nameHash().get());
      text(output, actualEncodingKey);
      text(output, encodedVerifierSemantics);
      output.flush();
      return "EF1_"
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void text(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static void source(DataOutputStream output, ResolvedSourceIdentity source)
      throws IOException {
    text(output, source.namespace());
    text(output, source.immutableSourceKey());
    text(output, source.verificationSemantics());
  }
}
