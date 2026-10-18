package io.vibeminer.infrastructure.itemexport;

import static io.vibeminer.application.itemexport.ItemEvidenceException.Code.*;

import io.vibeminer.application.CancellationToken;
import io.vibeminer.application.itemexport.*;
import io.vibeminer.domain.*;
import io.vibeminer.domain.item.*;
import io.vibeminer.domain.selectedfile.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Explicit version-1 projection; source payloads and arbitrary Java objects are never serialized.
 */
public final class ItemEvidenceJsonEncoder {
  public byte[] encode(
      ItemEvidenceSnapshot snapshot, Instant documentCreatedAt, CancellationToken cancellation) {
    Objects.requireNonNull(snapshot);
    Objects.requireNonNull(documentCreatedAt);
    var json = new Json(Objects.requireNonNull(cancellation));
    var selection = snapshot.selection();
    json.value(
        object(
            "format",
            "VibeMiner.ItemEvidence",
            "schemaVersion",
            1,
            "documentCreatedAt",
            documentCreatedAt.toString(),
            "proofNotice",
            "Reproducibility commitments; not a self-authenticating Blizzard proof package.",
            "itemRecordId",
            selection.recordId(),
            "itemTableFileDataId",
            selection.item().evidence().association().descriptor().association().fileDataId(),
            "sparseTableFileDataId",
            selection
                .names()
                .map(
                    p ->
                        (Object)
                            p.names()
                                .evidence()
                                .association()
                                .descriptor()
                                .association()
                                .fileDataId())
                .orElse(null),
            "verification",
            object(
                "startedAt",
                snapshot.verificationStartedAt().toString(),
                "completedAt",
                snapshot.verificationCompletedAt().toString(),
                "generationFingerprint",
                snapshot.generationFingerprint()),
            "itemSource",
            item(selection.item()),
            "sparseSource",
            selection.names().map(p -> (Object) sparse(p.names())).orElse(null),
            "row",
            object(
                "physicalOrdinal",
                snapshot.row().physicalOrdinal(),
                "sectionOrdinal",
                snapshot.row().sectionOrdinal(),
                "integers",
                array(snapshot.row().values(), ItemEvidenceJsonEncoder::integer)),
            "name",
            name(snapshot)));
    cancellation.throwIfCancellationRequested();
    return json.output.toByteArray();
  }

  private static Value integer(ItemPhysicalRow.IntegerCandidate v) {
    return object(
        "field",
        v.field().name(),
        "rawWidth",
        v.rawWidth(),
        "rawHex",
        rawHex(v.rawBits(), v.rawWidth()),
        "physicalInterpretation",
        v.physicalInterpretation().name(),
        "candidateDecimal",
        v.candidateSign() == ItemArtifactDefinition.Sign.UNSIGNED
            ? Long.toUnsignedString(v.candidateValue())
            : Long.toString(v.candidateValue()),
        "candidateBits",
        v.candidateBits(),
        "candidateSign",
        v.candidateSign().name());
  }

  private static String rawHex(long bits, int width) {
    String hex = Long.toHexString(bits);
    return "0".repeat((width + 3) / 4 - hex.length()) + hex;
  }

  private static Value name(ItemEvidenceSnapshot s) {
    if (s.name().isEmpty())
      return object("state", "NOT_INCLUDED", "candidate", null, "copySourceId", null);
    var n = s.name().orElseThrow();
    return object(
        "state",
        n.outcome().name(),
        "copySourceId",
        optional(n.copySourceId()),
        "candidate",
        n.candidate()
            .map(
                c ->
                    (Object)
                        object(
                            "value",
                            c.value(),
                            "physicalRecordId",
                            c.physicalRecordId(),
                            "physicalOrdinal",
                            c.physicalOrdinal(),
                            "sectionOrdinal",
                            c.sectionOrdinal(),
                            "decodedOffset",
                            decimal(c.decodedOffset()),
                            "recordBytes",
                            c.recordBytes(),
                            "prefixBytes",
                            c.prefixBytes(),
                            "utf8Bytes",
                            c.utf8Bytes()))
            .orElse(null));
  }

  private static Value item(PreparedItemProjection p) {
    var i = p.definition().interpretation();
    var s = i.schema();
    var c = p.coverage();
    return object(
        "artifactId",
        p.artifactId(),
        "sources",
        array(p.definition().sources(), ItemEvidenceJsonEncoder::physical),
        "interpretation",
        object(
            "decoderRevision",
            i.decoderRevision(),
            "indexRevision",
            i.indexRevision(),
            "tableHash",
            rawHex(i.tableHash(), 32),
            "layoutHash",
            rawHex(i.layoutHash(), 32),
            "sectionOrdinal",
            i.sectionOrdinal(),
            "sectionDecodedOffset",
            decimal(i.sectionDecodedOffset()),
            "sectionDecodedBytes",
            decimal(i.sectionDecodedBytes()),
            "locale",
            i.locale(),
            "optionsRevision",
            i.optionsRevision(),
            "proofSemantics",
            i.proofSemantics(),
            "schema",
            object(
                "provider",
                s.provider(),
                "revision",
                s.revision(),
                "definitionPath",
                s.definitionPath(),
                "definitionSha256",
                s.definitionSha256(),
                "selectionDigest",
                s.selectionDigest(),
                "license",
                s.license(),
                "attribution",
                s.attribution(),
                "fields",
                array(
                    s.fields(),
                    f ->
                        object(
                            "field",
                            f.field().name(),
                            "physicalFieldOrdinal",
                            f.physicalFieldOrdinal(),
                            "candidateBits",
                            f.candidateBits(),
                            "sign",
                            f.sign().name())))),
        "coverage",
        object(
            "sectionOrdinal",
            c.sectionOrdinal(),
            "physicalRows",
            decimal(c.physicalRows()),
            "unindexedCopyDeclarations",
            decimal(c.unindexedCopyDeclarations()),
            "excludedSections",
            c.excludedSections()),
        "evidence",
        evidence(p.evidence()));
  }

  private static Value sparse(PreparedSparseNames p) {
    var i = p.definition().interpretation();
    var s = i.schema();
    var c = p.coverage();
    return object(
        "artifactId",
        p.artifactId(),
        "sources",
        array(p.definition().sources(), ItemEvidenceJsonEncoder::physical),
        "interpretation",
        object(
            "decoderRevision",
            i.decoderRevision(),
            "indexRevision",
            i.indexRevision(),
            "tableHash",
            rawHex(i.tableHash(), 32),
            "layoutHash",
            rawHex(i.layoutHash(), 32),
            "sectionOrdinal",
            i.sectionOrdinal(),
            "metadataPrefixBytes",
            decimal(i.metadataPrefixBytes()),
            "recordAreaOffset",
            decimal(i.recordAreaOffset()),
            "recordAreaBytes",
            decimal(i.recordAreaBytes()),
            "suffixOffset",
            decimal(i.suffixOffset()),
            "suffixBytes",
            decimal(i.suffixBytes()),
            "locale",
            i.locale(),
            "optionsRevision",
            i.optionsRevision(),
            "proofSemantics",
            i.proofSemantics(),
            "schema",
            object(
                "provider",
                s.provider(),
                "revision",
                s.revision(),
                "definitionPath",
                s.definitionPath(),
                "definitionSha256",
                s.definitionSha256(),
                "selectionDigest",
                s.selectionDigest(),
                "license",
                s.license(),
                "attribution",
                s.attribution(),
                "prefixFields",
                array(s.prefixFields(), v -> v))),
        "coverage",
        object(
            "sectionOrdinal",
            c.sectionOrdinal(),
            "physicalRows",
            decimal(c.physicalRows()),
            "unindexedCopyDeclarations",
            decimal(c.unindexedCopyDeclarations()),
            "excludedSections",
            c.excludedSections(),
            "projectedUtf8Bytes",
            decimal(c.projectedUtf8Bytes())),
        "evidence",
        evidence(p.evidence()));
  }

  private static Value evidence(ItemProjectionEvidence e) {
    return object(
        "association",
        association(e.association()),
        "localVerification",
        object(
            "object",
            physical(e.localVerification().object()),
            "verifiedAt",
            e.localVerification().verifiedAt().toString(),
            "verifierRevision",
            e.localVerification().verifierRevision()),
        "chunks",
        array(
            e.chunks(),
            c ->
                object(
                    "ordinal",
                    c.ordinal(),
                    "encodedOffset",
                    decimal(c.encodedOffset()),
                    "encodedBytes",
                    decimal(c.encodedBytes()),
                    "encodedMd5",
                    c.encodedMd5(),
                    "decodedOffset",
                    decimal(c.decodedOffset()),
                    "decodedBytes",
                    decimal(c.decodedBytes()),
                    "verifierRevision",
                    c.verifierRevision())),
        "windows",
        array(
            e.windows(), w -> object("offset", decimal(w.offset()), "length", decimal(w.length()))),
        "fullDecoded",
        e.fullDecoded()
            .map(
                a ->
                    (Object)
                        object(
                            "contentKey",
                            a.contentKey(),
                            "actualDecodedBytes",
                            decimal(a.actualDecodedBytes()),
                            "verifierRevision",
                            a.verifierRevision()))
            .orElse(null));
  }

  private static Value association(SelectedFileAssociation a) {
    var d = a.descriptor();
    var v = d.association();
    var p = a.proof();
    var r = a.representation();
    return object(
        "snapshotId",
        a.snapshotId(),
        "role",
        a.role(),
        "build",
        build(v.build()),
        "variant",
        object(
            "rootSource",
            source(v.rootSource()),
            "fileDataId",
            v.fileDataId(),
            "contentKey",
            v.contentKey(),
            "localeFlags",
            rawHex(v.localeFlags(), 32),
            "contentFlags",
            rawHex(v.contentFlags(), 32),
            "rawFlagsFirst",
            rawHex(v.rawFlagsFirst(), 32),
            "rawFlagsSecond",
            rawHex(v.rawFlagsSecond(), 32),
            "rawFlagsThird",
            rawHex(v.rawFlagsThird(), 8),
            "nameHash",
            v.nameHash().orElse(null)),
        "actualEncodingKey",
        d.actualEncodingKey(),
        "encodedVerifierSemantics",
        d.encodedVerifierSemantics(),
        "configuration",
        alias(a.configuration()),
        "root",
        alias(a.root()),
        "associationProof",
        object(
            "rootSource",
            source(p.rootSource()),
            "encodingBootstrapSource",
            source(p.encodingBootstrapSource()),
            "verifierRevision",
            p.verifierRevision(),
            "proofDigest",
            p.proofDigest(),
            "declaredDecodedBytes",
            optional(p.declaredDecodedBytes()),
            "authenticatedCandidates",
            array(
                p.authenticatedCandidates(),
                c ->
                    object(
                        "encodingKey",
                        c.encodingKey(),
                        "encodedBytes",
                        optional(c.encodedBytes()),
                        "specificationIndex",
                        optional(c.specificationIndex())))),
        "representation",
        object(
            "slot",
            object("namespace", r.slot().namespace(), "contentKey", r.slot().contentKey()),
            "encoded",
            alias(r.encoded()),
            "scope",
            r.scope().name(),
            "sourceReceipt",
            receipt(r.sourceReceipt())));
  }

  private static Value build(BuildSnapshot b) {
    return object(
        "snapshotId",
        b.snapshotId(),
        "productId",
        b.productId(),
        "editionLabel",
        b.editionLabel(),
        "channel",
        b.channel(),
        "region",
        b.region(),
        "versionLabel",
        b.versionLabel(),
        "buildConfig",
        b.buildConfig(),
        "cdnConfig",
        b.cdnConfig(),
        "observedAt",
        b.observedAt().toString(),
        "fixture",
        b.fixture(),
        "upstreamBuildId",
        b.evidence().upstreamBuildId(),
        "productConfig",
        b.evidence().productConfig(),
        "sourceDocuments",
        array(
            b.evidence().documents(),
            d ->
                object(
                    "sourceUrl",
                    d.sourceUrl(),
                    "capturedAt",
                    d.capturedAt().toString(),
                    "sha256",
                    d.sha256())));
  }

  private static Value receipt(EncodedSourceReceipt r) {
    return object(
        "kind",
        r.kind().name(),
        "actualResourceUrl",
        r.actualResourceUrl(),
        "resourceKey",
        r.resourceKey(),
        "parentWholeBytes",
        decimal(r.parentWholeBytes()),
        "childOffset",
        decimal(r.childOffset()),
        "childEncodedBytes",
        decimal(r.childEncodedBytes()),
        "observedAt",
        r.observedAt().toString(),
        "payloadRequestCount",
        decimal(r.payloadRequestCount()),
        "payloadResponseBytes",
        decimal(r.payloadResponseBytes()),
        "observationDigest",
        r.observationDigest(),
        "archiveMapping",
        r.archiveMapping()
            .map(
                m ->
                    (Object)
                        object(
                            "indexIdentity",
                            m.indexIdentity(),
                            "footerSha256",
                            m.footerSha256(),
                            "tocSha256",
                            m.tocSha256(),
                            "pageSha256",
                            m.pageSha256(),
                            "verifierRevision",
                            m.verifierRevision(),
                            "advertisedIndexBytes",
                            optional(m.advertisedIndexBytes()),
                            "authenticatedIndexBytes",
                            m.hasAuthenticatedIndexGeometry()
                                ? decimal(m.authenticatedIndexBytes())
                                : null))
            .orElse(null));
  }

  private static Value alias(VerifiedSourceAlias a) {
    return object(
        "source",
        source(a.source()),
        "object",
        physical(a.object()),
        "evidence",
        object(
            "verifierSemantics",
            a.evidence().verifierSemantics(),
            "sourceUrl",
            a.evidence().sourceUrl(),
            "verifiedAt",
            a.evidence().verifiedAt().toString()));
  }

  private static Value source(ResolvedSourceIdentity s) {
    return object(
        "namespace",
        s.namespace(),
        "immutableSourceKey",
        s.immutableSourceKey(),
        "verificationSemantics",
        s.verificationSemantics());
  }

  private static Value physical(StoredObject o) {
    return object("sha256", o.digest().value(), "length", decimal(o.length()));
  }

  private static String decimal(long n) {
    return Long.toString(n);
  }

  private static Object optional(OptionalLong n) {
    return n.isPresent() ? decimal(n.getAsLong()) : null;
  }

  @FunctionalInterface
  private interface Value {
    void write(Json json);
  }

  private static Value object(Object... fields) {
    return j -> {
      j.ascii("{");
      for (int i = 0; i < fields.length; i += 2) {
        if (i != 0) j.ascii(",");
        j.string((String) fields[i]);
        j.ascii(":");
        j.value(fields[i + 1]);
      }
      j.ascii("}");
    };
  }

  private static <T> Value array(List<T> values, Function<T, Object> project) {
    return j -> {
      j.ascii("[");
      boolean first = true;
      for (T v : values) {
        if (!first) j.ascii(",");
        first = false;
        j.value(project.apply(v));
      }
      j.ascii("]");
    };
  }

  /** Writes bounded bytes directly; no intermediate JSON string or replacement Unicode encoding. */
  private static final class Json {
    final ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
    final CancellationToken cancellation;

    Json(CancellationToken cancellation) {
      this.cancellation = cancellation;
    }

    void value(Object value) {
      cancellation.throwIfCancellationRequested();
      if (value == null) ascii("null");
      else if (value instanceof Value v) v.write(this);
      else if (value instanceof String s) string(s);
      else if (value instanceof Boolean || value instanceof Integer || value instanceof Long)
        ascii(value.toString());
      else throw new AssertionError("Unapproved JSON projection type");
    }

    void ascii(String s) {
      for (int i = 0; i < s.length(); i++) put(s.charAt(i));
    }

    void put(int b) {
      cancellation.throwIfCancellationRequested();
      if (output.size() >= ItemEvidenceReceipt.MAXIMUM_UTF8_BYTES)
        throw new ItemEvidenceException(
            LIMIT_EXCEEDED, "Item evidence exceeds the version-1 2 MiB UTF-8 ceiling");
      output.write(b);
    }

    void string(String s) {
      put('"');
      for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        int cp = ch;
        if (Character.isHighSurrogate(ch)) {
          if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))
            throw invalidUnicode();
          cp = Character.toCodePoint(ch, s.charAt(++i));
        } else if (Character.isLowSurrogate(ch)) throw invalidUnicode();
        if (cp == '"' || cp == '\\') {
          put('\\');
          put(cp);
        } else if (cp < 32) {
          ascii(String.format(Locale.ROOT, "\\u%04x", cp));
        } else if (cp < 128) put(cp);
        else if (cp < 2048) {
          put(0xc0 | (cp >> 6));
          put(0x80 | (cp & 63));
        } else if (cp < 65536) {
          put(0xe0 | (cp >> 12));
          put(0x80 | ((cp >> 6) & 63));
          put(0x80 | (cp & 63));
        } else {
          put(0xf0 | (cp >> 18));
          put(0x80 | ((cp >> 12) & 63));
          put(0x80 | ((cp >> 6) & 63));
          put(0x80 | (cp & 63));
        }
      }
      put('"');
    }

    ItemEvidenceException invalidUnicode() {
      return new ItemEvidenceException(INVALID_REQUEST, "Export text is not strict Unicode");
    }
  }
}
