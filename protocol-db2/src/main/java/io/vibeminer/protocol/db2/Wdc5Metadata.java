package io.vibeminer.protocol.db2;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Observed metadata declarations, never validated record bodies, schema semantics or source hashes.
 */
public record Wdc5Metadata(
    Wdc5Header header,
    List<Section> sections,
    List<Field> fields,
    List<Storage> storage,
    long metadataBytesRead,
    long declaredAuxiliaryDataEnd,
    Set<Capability> capabilities) {
  public Wdc5Metadata {
    sections = List.copyOf(sections);
    fields = List.copyOf(fields);
    storage = List.copyOf(storage);
    capabilities = Set.copyOf(capabilities);
  }

  public enum Capability {
    BODY_NOT_VALIDATED,
    RECORD_DECODER_UNAVAILABLE,
    SCHEMA_SEMANTICS_UNVERIFIED,
    ENCRYPTION_KEY_DECLARED,
    SPARSE_LAYOUT_OBSERVED,
    UNKNOWN_STORAGE_KIND,
    AUXILIARY_ACCOUNTING_UNVERIFIED,
    UNSUPPORTED_STORAGE_PARAMETERS,
    UNSUPPORTED_SECTION_PARAMETERS
  }

  /** A nonzero key is a declaration, not proof the section is readable or unreadable. */
  public record Section(
      String encryptionKeyIdentifier,
      long fileOffset,
      long recordCount,
      long stringTableSize,
      long offsetRecordsEndOffset,
      long idListSize,
      long relationshipDataSize,
      long offsetMapIdCount,
      long copyTableCount,
      long declaredEndOffset) {
    public boolean encryptionKeyDeclared() {
      return !encryptionKeyIdentifier.equals("0000000000000000");
    }
  }

  /** Both signed views match the pinned physical int16 fields; column offsets below are uint16. */
  public record Field(short bits, short offset) {}

  public enum StorageKind {
    NONE,
    IMMEDIATE,
    COMMON,
    PALLET,
    PALLET_ARRAY,
    SIGNED_IMMEDIATE
  }

  /**
   * Union words remain unsigned raw facts; no bit-width/cardinality/default-value decoding occurs.
   */
  public record Storage(
      int recordOffset,
      int size,
      long additionalDataSize,
      long rawKind,
      Optional<StorageKind> kind,
      long parameter1,
      long parameter2,
      long parameter3) {}
}
