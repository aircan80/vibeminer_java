package io.vibeminer.application.buildlibrary;

import io.vibeminer.application.discoveryhistory.DiscoveryHistoryFilter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Literal version/build search and exact observed-region scope, applied before paging. */
public record BuildLibraryQuery(
    String productId,
    BuildLibraryProjection.Filter filter,
    Optional<String> search,
    Optional<String> region) {
  public BuildLibraryQuery(String productId, BuildLibraryProjection.Filter filter) {
    this(productId, filter, Optional.empty(), Optional.empty());
  }

  public BuildLibraryQuery {
    new DiscoveryHistoryFilter(productId, region, Optional.empty());
    Objects.requireNonNull(search);
    search = search.map(String::strip).filter(value -> !value.isEmpty());
    search.ifPresent(
        value -> {
          if (value.length() > 128
              || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
              || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Literal search must be bounded Unicode text");
        });
    Objects.requireNonNull(filter);
  }

  public boolean matchesKey(BuildReferenceGroupKey key) {
    if (!productId.equals(key.productId())) return false;
    return search.isEmpty()
        || key.versionLabel()
            .toLowerCase(Locale.ROOT)
            .contains(search.get().toLowerCase(Locale.ROOT))
        || key.upstreamBuildId().contains(search.get());
  }

  public boolean matches(BuildLibraryProjection.Row row) {
    return row.matches(filter)
        && (filter != BuildLibraryProjection.Filter.CURRENTLY_ADVERTISED
            || region.isEmpty()
            || row.regionalAdvertisements()
                    .getOrDefault(region.get(), BuildLibraryProjection.Advertisement.UNKNOWN)
                == BuildLibraryProjection.Advertisement.PRESENT_IN_CHECKED_SCOPE)
        && matchesKey(row.group().key())
        && (region.isEmpty()
            || row.group().references().stream()
                .anyMatch(reference -> region.get().equals(reference.region())));
  }

  public String identity() {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      for (var field :
          List.of(
              "build-library-query-v2",
              productId,
              filter.name(),
              search.orElse(""),
              region.orElse(""))) {
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
