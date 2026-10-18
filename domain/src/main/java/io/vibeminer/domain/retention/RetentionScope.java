package io.vibeminer.domain.retention;

import java.io.*;
import java.security.*;
import java.util.*;

/** Immutable exact-build definition; pin state is separate and does not change identity. */
public record RetentionScope(
    String snapshotId, ScopeKind kind, List<ScopeRequirement> requirements) {
  public RetentionScope {
    if (snapshotId == null || !snapshotId.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid snapshot identity");
    Objects.requireNonNull(kind);
    requirements =
        requirements.stream().sorted(Comparator.comparing(ScopeRequirement::role)).toList();
    var roles = new HashSet<String>();
    for (var requirement : requirements)
      if (!roles.add(requirement.role()))
        throw new IllegalArgumentException("Duplicate retention role");
    if (requirements.isEmpty() || requirements.size() > 256)
      throw new IllegalArgumentException("Invalid requirement count");
    if (kind == ScopeKind.INSTALL_METADATA
            && !roles.equals(Set.of("BUILD_CONFIG", "INSTALL_MANIFEST"))
        || kind == ScopeKind.ROOT_METADATA
            && !roles.equals(Set.of("BUILD_CONFIG", "ROOT_MANIFEST")))
      throw new IllegalArgumentException(
          "Scope must declare its supported offline dependency closure");
  }

  public String scopeId() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeUTF("vibeminer-retention-scope-v1");
      out.writeUTF(snapshotId);
      out.writeUTF(kind.name());
      out.writeInt(requirements.size());
      for (var requirement : requirements) {
        out.writeUTF(requirement.role());
        out.writeBoolean(requirement.source().isPresent());
        if (requirement.source().isPresent()) {
          var source = requirement.source().get();
          out.writeUTF(source.namespace());
          out.writeUTF(source.immutableSourceKey());
          out.writeUTF(source.verificationSemantics());
        }
        writeSize(out, requirement.encodedBytes());
        writeSize(out, requirement.logicalDecodedBytes());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Cannot derive scope identity", impossible);
    }
  }

  private static void writeSize(DataOutputStream out, OptionalLong size) throws IOException {
    out.writeBoolean(size.isPresent());
    if (size.isPresent()) out.writeLong(size.getAsLong());
  }
}
