package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.DiscoveryHistoryFilter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public record BuildLibraryQuery(String productId, BuildLibraryProjection.Filter filter) {
  public BuildLibraryQuery {
    new DiscoveryHistoryFilter(productId, Optional.empty(), Optional.empty());
    Objects.requireNonNull(filter);
  }

  public String identity() {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (var field : List.of("build-library-query-v1", productId, filter.name())) {
        var bytes = field.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
