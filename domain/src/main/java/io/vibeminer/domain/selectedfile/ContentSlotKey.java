package io.vibeminer.domain.selectedfile;

/** One retained representation of a declared CKey, not a decoded-content assertion. */
public record ContentSlotKey(String namespace, String contentKey) {
  public static final String WOW_NAMESPACE = "wow";

  public ContentSlotKey {
    if (!WOW_NAMESPACE.equals(namespace))
      throw new IllegalArgumentException(
          "Only the explicitly supported WoW content domain is available");
    contentKey = SelectedFileValues.key(contentKey);
  }
}
