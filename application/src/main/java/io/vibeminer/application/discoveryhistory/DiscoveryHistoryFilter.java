package io.vibeminer.application.discoveryhistory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exact raw product and optional region/channel; friendly labels are not filter identity. */
public record DiscoveryHistoryFilter(
    String productId, Optional<String> region, Optional<String> channel) {
  public DiscoveryHistoryFilter {
    productId = HistoryValues.product(productId);
    Objects.requireNonNull(region);
    Objects.requireNonNull(channel);
    region.ifPresent(HistoryValues::region);
    channel.ifPresent(value -> HistoryValues.text(value, 4096));
  }

  public String identity() {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      part(hash, "discovery-history-filter-v1");
      part(hash, productId);
      part(hash, region.isPresent() ? "1" : "0");
      part(hash, region.orElse(""));
      part(hash, channel.isPresent() ? "1" : "0");
      part(hash, channel.orElse(""));
      return HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void part(MessageDigest hash, String value) {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    hash.update(bytes);
  }
}
