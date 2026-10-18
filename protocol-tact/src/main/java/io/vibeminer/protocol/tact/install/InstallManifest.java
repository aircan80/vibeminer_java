package io.vibeminer.protocol.tact.install;

import java.util.List;

/** CDN install-manifest metadata; names are never resolved as filesystem paths. */
public final class InstallManifest {
    public record Entry(String name,String contentKey,long decodedSize) {}
    public static final class Tag {
        private final String name;
        private final int type,entryCount;
        private final byte[] membership;
        Tag(String name,int type,int entryCount,byte[] membership){this.name=name;this.type=type;this.entryCount=entryCount;this.membership=membership.clone();}
        public String name(){return name;}
        public int type(){return type;}
        /** Original manifest order, MSB-first; padding bits are not entries. */
        public boolean includesEntry(int index){
            java.util.Objects.checkIndex(index,entryCount);
            return (membership[index/8] & (0x80 >>> (index%8)))!=0;
        }
    }
    private final List<Entry> entries;
    private final List<Tag> tags;
    InstallManifest(List<Entry> entries,List<Tag> tags){this.entries=List.copyOf(entries);this.tags=List.copyOf(tags);}
    public List<Entry> entries(){return entries;}
    public List<Tag> tags(){return tags;}
    public List<Tag> tagsForEntry(int index){java.util.Objects.checkIndex(index,entries.size());return tags.stream().filter(tag->tag.includesEntry(index)).toList();}
}
