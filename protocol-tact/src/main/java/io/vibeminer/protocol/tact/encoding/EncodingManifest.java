package io.vibeminer.protocol.tact.encoding;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Immutable EN lookup metadata. Candidate order is preserved; selection belongs to acquisition policy. */
public final class EncodingManifest {
    public record ContentEntry(String contentKey,long decodedSize,List<String> encodingKeys){
        public ContentEntry {encodingKeys=List.copyOf(encodingKeys);}
    }
    public record EncodedEntry(String encodingKey,long encodedSize,long specIndex,String encodingSpec) {}
    private final Map<String,ContentEntry> contents;
    private final Map<String,EncodedEntry> encodings;
    private final String fileEncodingSpec;
    EncodingManifest(Map<String,ContentEntry> contents,Map<String,EncodedEntry> encodings,String fileEncodingSpec){
        this.contents=Map.copyOf(contents);this.encodings=Map.copyOf(encodings);this.fileEncodingSpec=fileEncodingSpec;
    }
    public Map<String,ContentEntry> contentEntries(){return contents;}
    public Map<String,EncodedEntry> encodingEntries(){return encodings;}
    /** Opaque bounded file-own ESpec trailer, preserved without interpreting its grammar. */
    public String fileEncodingSpec(){return fileEncodingSpec;}
    public Optional<ContentEntry> findContent(String contentKey){return Optional.ofNullable(contents.get(canonicalKey(contentKey)));}
    public Optional<EncodedEntry> findEncoding(String encodingKey){return Optional.ofNullable(encodings.get(canonicalKey(encodingKey)));}
    private static String canonicalKey(String key){
        if(key==null || !key.matches("[0-9a-fA-F]{32}"))throw new IllegalArgumentException("Lookup requires a complete 16-byte key");
        return key.toLowerCase(Locale.ROOT);
    }
}
