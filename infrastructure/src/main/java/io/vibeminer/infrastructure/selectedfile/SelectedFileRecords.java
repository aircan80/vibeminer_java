package io.vibeminer.infrastructure.selectedfile;

import io.vibeminer.application.selectedfile.SelectedFileException;
import io.vibeminer.domain.*;
import io.vibeminer.domain.selectedfile.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Bounded, versioned typed metadata only. No Java serialization and no game-payload field. */
public final class SelectedFileRecords {
  static final int MAX_BYTES = 65536;

  private SelectedFileRecords() {}

  @FunctionalInterface
  interface Writer {
    void write(DataOutputStream out) throws IOException;
  }

  @FunctionalInterface
  interface Reader<T> {
    T read(DataInputStream in) throws IOException;
  }

  private static byte[] encode(Writer writer) {
    try {
      var bytes = new ByteArrayOutputStream();
      writer.write(new DataOutputStream(bytes));
      if (bytes.size() > MAX_BYTES) throw invalid("Selected-file metadata exceeds limit");
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw invalid("Cannot encode selected-file metadata", failure);
    }
  }

  private static <T> T decode(byte[] bytes, Reader<T> reader) {
    if (bytes == null || bytes.length > MAX_BYTES)
      throw invalid("Missing or oversized selected-file metadata");
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      var value = reader.read(in);
      if (in.read() != -1) throw invalid("Trailing selected-file metadata");
      return value;
    } catch (IOException failure) {
      throw invalid("Invalid selected-file metadata", failure);
    } catch (RuntimeException failure) {
      if (failure instanceof SelectedFileException typed) throw typed;
      throw invalid("Invalid selected-file metadata", failure);
    }
  }

  public static byte[] representation(ContentRepresentation value) {
    return encode(
        out -> {
          text(out, "VMER2");
          representation(out, value);
        });
  }

  public static ContentRepresentation representation(byte[] bytes) {
    return decode(
        bytes,
        in -> {
          var version = text(in);
          if (!version.equals("VMER1") && !version.equals("VMER2"))
            throw invalid("Unsupported representation metadata");
          return representation(in, version.equals("VMER2"));
        });
  }

  static byte[] association(SelectedFileAssociation value) {
    return encode(
        out -> {
          text(out, "VMSA2");
          var descriptor = value.descriptor();
          var variant = descriptor.association();
          text(out, variant.build().snapshotId());
          source(out, variant.rootSource());
          out.writeLong(variant.fileDataId());
          text(out, variant.contentKey());
          out.writeLong(variant.localeFlags());
          out.writeLong(variant.contentFlags());
          out.writeLong(variant.rawFlagsFirst());
          out.writeLong(variant.rawFlagsSecond());
          out.writeInt(variant.rawFlagsThird());
          out.writeBoolean(variant.nameHash().isPresent());
          if (variant.nameHash().isPresent()) text(out, variant.nameHash().get());
          text(out, descriptor.actualEncodingKey());
          text(out, descriptor.encodedVerifierSemantics());
          alias(out, value.configuration());
          alias(out, value.root());
          source(out, value.proof().rootSource());
          source(out, value.proof().encodingBootstrapSource());
          text(out, value.proof().verifierRevision());
          text(out, value.proof().proofDigest());
          optionalSize(out, value.proof().declaredDecodedBytes());
          out.writeInt(value.proof().authenticatedCandidates().size());
          for (var candidate : value.proof().authenticatedCandidates()) {
            text(out, candidate.encodingKey());
            optionalSize(out, candidate.encodedBytes());
            optionalSize(out, candidate.specificationIndex());
          }
        });
  }

  static SelectedFileAssociation association(
      byte[] bytes, BuildSnapshot build, ContentRepresentation representation) {
    return decode(
        bytes,
        in -> {
          var version = text(in);
          if ((!version.equals("VMSA1") && !version.equals("VMSA2"))
              || !text(in).equals(build.snapshotId()))
            throw invalid("Association snapshot/version mismatch");
          var root = source(in);
          long fdid = in.readLong();
          var ckey = text(in);
          long locale = in.readLong(),
              content = in.readLong(),
              first = in.readLong(),
              second = in.readLong();
          int third = in.readInt();
          Optional<String> name = in.readBoolean() ? Optional.of(text(in)) : Optional.empty();
          var descriptor =
              new SelectedFileDescriptor(
                  new FileVariantAssociation(
                      build, root, fdid, ckey, locale, content, first, second, third, name),
                  text(in),
                  text(in));
          var config = alias(in);
          var rootReceipt = alias(in);
          var proofRoot = source(in);
          var bootstrap = source(in);
          var revision = text(in);
          var digest = text(in);
          var decoded = OptionalLong.empty();
          var candidates = new ArrayList<EncodedCandidate>();
          if (version.equals("VMSA2")) {
            decoded = optionalSize(in);
            int count = in.readInt();
            if (count < 0 || count > 255) throw invalid("Invalid authenticated candidate count");
            for (int i = 0; i < count; i++)
              candidates.add(new EncodedCandidate(text(in), optionalSize(in), optionalSize(in)));
          }
          var proof =
              new FileAssociationProof(proofRoot, bootstrap, revision, digest, decoded, candidates);
          return new SelectedFileAssociation(
              descriptor, config, rootReceipt, proof, representation);
        });
  }

  private static void representation(DataOutputStream out, ContentRepresentation value)
      throws IOException {
    text(out, value.slot().namespace());
    text(out, value.slot().contentKey());
    alias(out, value.encoded());
    text(out, value.scope().name());
    var receipt = value.sourceReceipt();
    text(out, receipt.kind().name());
    text(out, receipt.actualResourceUrl());
    text(out, receipt.resourceKey());
    out.writeLong(receipt.parentWholeBytes());
    out.writeLong(receipt.childOffset());
    out.writeLong(receipt.childEncodedBytes());
    text(out, receipt.observedAt().toString());
    out.writeLong(receipt.payloadRequestCount());
    out.writeLong(receipt.payloadResponseBytes());
    text(out, receipt.observationDigest());
    out.writeBoolean(receipt.archiveMapping().isPresent());
    if (receipt.archiveMapping().isPresent()) {
      var proof = receipt.archiveMapping().get();
      text(out, proof.indexIdentity());
      text(out, proof.footerSha256());
      text(out, proof.tocSha256());
      text(out, proof.pageSha256());
      text(out, proof.verifierRevision());
      out.writeBoolean(proof.advertisedIndexBytes().isPresent());
      if (proof.advertisedIndexBytes().isPresent())
        out.writeLong(proof.advertisedIndexBytes().getAsLong());
      out.writeLong(proof.authenticatedIndexBytes());
    }
  }

  private static ContentRepresentation representation(DataInputStream in, boolean geometry)
      throws IOException {
    var slot = new ContentSlotKey(text(in), text(in));
    var encoded = alias(in);
    var scope = EncodedScope.valueOf(text(in));
    var kind = EncodedSourceReceipt.Kind.valueOf(text(in));
    var url = text(in);
    var resource = text(in);
    long total = in.readLong(), offset = in.readLong(), length = in.readLong();
    var at = Instant.parse(text(in));
    long requests = in.readLong(), response = in.readLong();
    var digest = text(in);
    Optional<ArchiveMappingProof> mapping = Optional.empty();
    if (in.readBoolean()) {
      var index = text(in);
      var footer = text(in);
      var toc = text(in);
      var page = text(in);
      var revision = text(in);
      var advertised =
          geometry && in.readBoolean() ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
      long authenticated = geometry ? in.readLong() : 0;
      mapping =
          Optional.of(
              new ArchiveMappingProof(
                  index, footer, toc, page, revision, advertised, authenticated));
    }
    return new ContentRepresentation(
        slot,
        encoded,
        scope,
        new EncodedSourceReceipt(
            kind, url, resource, total, offset, length, at, requests, response, digest, mapping));
  }

  private static void alias(DataOutputStream out, VerifiedSourceAlias value) throws IOException {
    source(out, value.source());
    text(out, value.object().digest().value());
    out.writeLong(value.object().length());
    text(out, value.evidence().sourceUrl());
    text(out, value.evidence().verifiedAt().toString());
  }

  private static VerifiedSourceAlias alias(DataInputStream in) throws IOException {
    var source = source(in);
    var object = new StoredObject(new ObjectDigest(text(in)), in.readLong());
    return new VerifiedSourceAlias(
        source,
        object,
        new SourceVerificationEvidence(
            source.verificationSemantics(), text(in), Instant.parse(text(in))));
  }

  private static void source(DataOutputStream out, ResolvedSourceIdentity value)
      throws IOException {
    text(out, value.namespace());
    text(out, value.immutableSourceKey());
    text(out, value.verificationSemantics());
  }

  private static ResolvedSourceIdentity source(DataInputStream in) throws IOException {
    return new ResolvedSourceIdentity(text(in), text(in), text(in));
  }

  private static void optionalSize(DataOutputStream out, OptionalLong value) throws IOException {
    out.writeBoolean(value.isPresent());
    if (value.isPresent()) out.writeLong(value.getAsLong());
  }

  private static OptionalLong optionalSize(DataInputStream in) throws IOException {
    return in.readBoolean() ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
  }

  private static void text(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 16384) throw invalid("Metadata field too large");
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String text(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > 16384 || count > in.available())
      throw invalid("Invalid metadata field length");
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(in.readNBytes(count)))
        .toString();
  }

  static SelectedFileException invalid(String message) {
    return new SelectedFileException(SelectedFileException.Code.INTEGRITY_FAILURE, message);
  }

  static SelectedFileException invalid(String message, Throwable cause) {
    return new SelectedFileException(SelectedFileException.Code.INTEGRITY_FAILURE, message, cause);
  }
}
