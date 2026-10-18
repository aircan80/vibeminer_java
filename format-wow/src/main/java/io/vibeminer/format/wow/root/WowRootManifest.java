package io.vibeminer.format.wow.root;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Root membership metadata, not decoded game records or discovered filenames. */
public final class WowRootManifest {
    /** Original v2 flag components; v1 stores its single word in first. */
    public record RawContentFlags(long first,long second,int third) {}
    /** contentFlags is a lossy legacy OR for v2. Name hashes are unsigned lookup IDs, including present zero. */
    public record Entry(long fileDataId,String contentKey,long localeFlags,long contentFlags,Optional<String> nameHash,RawContentFlags rawContentFlags) {}
    private final int headerSize,version;
    private final long recordCount,namedRecordCount;
    private final Map<Long,List<Entry>> entries;
    WowRootManifest(int headerSize,int version,long recordCount,long namedRecordCount,Map<Long,List<Entry>> entries){
        this.headerSize=headerSize;this.version=version;this.recordCount=recordCount;this.namedRecordCount=namedRecordCount;
        var copy=new LinkedHashMap<Long,List<Entry>>();entries.forEach((id,variants)->copy.put(id,List.copyOf(variants)));
        this.entries=java.util.Collections.unmodifiableMap(copy);
    }
    public int headerSize(){return headerSize;}
    public int version(){return version;}
    public long recordCount(){return recordCount;}
    public long namedRecordCount(){return namedRecordCount;}
    public Map<Long,List<Entry>> entriesByFileDataId(){return entries;}
    /** Returns all observed variants in block order. No locale/platform preference or fallback is applied. */
    public List<Entry> findFileDataId(long id){
        if(id<0 || id>0xffffffffL)throw new IllegalArgumentException("FileDataID must be unsigned 32-bit");
        return entries.getOrDefault(id,List.of());
    }
}
