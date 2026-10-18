package io.vibeminer.application.content;

import java.time.Instant;
import java.util.Objects;

/**
 * Scalar facts from a supported successful HEAD, not payload integrity or future availability.
 * Construction does not authenticate the official source or issue repair consent.
 */
public record MetadataRepairSourceObservation(
    String resourceUrl,
    String resourceKey,
    long wholeEncodedBytes,
    Instant observedAt,
    String observationDigest) {
  public MetadataRepairSourceObservation {
    Objects.requireNonNull(observedAt);
    if (resourceKey == null || !resourceKey.matches("[0-9a-f]{32}") || wholeEncodedBytes < 1)
      throw new IllegalArgumentException("Known exact original source geometry required");
    requireResourceUrl(resourceUrl, resourceKey);
    if (observationDigest == null || !observationDigest.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Bounded metadata observation digest required");
  }

  static void requireResourceUrl(String value, String key) {
    if (value == null || value.length() > 8192)
      throw new IllegalArgumentException("Bounded resource URL required");
    if (!value.startsWith("https://"))
      throw new IllegalArgumentException("HTTPS resource URL required");
    int pathStart = value.indexOf('/', "https://".length());
    if (pathStart < 0)
      throw new IllegalArgumentException("Resource URL must include its object path");
    String authority = value.substring("https://".length(), pathStart);
    String path = value.substring(pathStart);
    int portStart = authority.indexOf(':');
    String host = portStart < 0 ? authority : authority.substring(0, portStart);
    if (portStart >= 0) {
      String port = authority.substring(portStart + 1);
      if (!port.matches("[1-9][0-9]{0,4}") || Integer.parseInt(port) > 65535)
        throw new IllegalArgumentException("Invalid resource port");
    }
    String[] labels = host.split("\\.", -1);
    if (host.length() > 253 || labels.length < 2)
      throw new IllegalArgumentException("Bounded resource host required");
    for (String label : labels)
      if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
        throw new IllegalArgumentException("Invalid resource host label");
    // Official source authentication stays in the adapter. This value accepts
    // only the unescaped ASCII object paths emitted by the existing CDN routes.
    if (!path.matches("/[A-Za-z0-9._/-]+")
        || path.contains("//")
        || path.contains("/./")
        || path.contains("/../")
        || !path.endsWith("/" + key))
      throw new IllegalArgumentException("Unambiguous resource path for original EKey required");
  }
}
