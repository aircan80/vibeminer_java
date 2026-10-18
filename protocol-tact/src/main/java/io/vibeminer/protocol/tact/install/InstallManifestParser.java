package io.vibeminer.protocol.tact.install;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static io.vibeminer.protocol.tact.install.InstallManifestException.Reason.*;

/** Pure bounded parser for IN version 1 with 16-byte content keys. Input is decoded data. */
public final class InstallManifestParser {
    public record Limits(int maxBytes,int maxEntries,int maxTags,int maxNameBytes){
        public Limits {if(maxBytes<10 || maxEntries<0 || maxTags<0 || maxNameBytes<1)throw new IllegalArgumentException("Invalid install-manifest bounds");}
    }
    /** Caller owns verification of decoded CKey and keeps input unchanged during parsing. */
    public InstallManifest parse(byte[] decoded,Limits limits,BooleanSupplier cancelled) throws InstallManifestException {
        Objects.requireNonNull(decoded);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if(decoded.length>limits.maxBytes())throw error(LIMIT,"Install manifest byte limit exceeded");
        var reader=new Reader(decoded,limits.maxNameBytes(),cancelled);
        if(reader.u8()!='I' || reader.u8()!='N')throw error(MALFORMED,"Invalid IN manifest signature");
        if(reader.u8()!=1)throw error(UNSUPPORTED,"Unsupported IN manifest version");
        if(reader.u8()!=16)throw error(UNSUPPORTED,"Unsupported IN content-key length");
        int tagCount=reader.u16();long entryCount=reader.u32();
        if(tagCount>limits.maxTags() || entryCount>limits.maxEntries())throw error(LIMIT,"Install manifest count limit exceeded");
        // Even empty strings need a terminator, hash and size. Check before list allocation.
        long bitmapBytes=(entryCount+7)/8;
        long minimum=tagCount*(3+bitmapBytes)+entryCount*21;
        if(minimum>reader.remaining())throw error(MALFORMED,"Manifest counts exceed available input");
        int entries=(int)entryCount;
        var tags=new ArrayList<InstallManifest.Tag>(tagCount);
        for(int i=0;i<tagCount;i++){
            checkCancelled(cancelled);String name=reader.name();int type=reader.u16();
            tags.add(new InstallManifest.Tag(name,type,entries,reader.bytes((int)bitmapBytes)));
        }
        var records=new ArrayList<InstallManifest.Entry>(entries);
        for(int i=0;i<entries;i++){
            checkCancelled(cancelled);String name=reader.name();String key=HexFormat.of().formatHex(reader.bytes(16));long size=reader.u32();
            records.add(new InstallManifest.Entry(name,key,size));
        }
        if(reader.remaining()!=0)throw error(MALFORMED,"Trailing IN manifest bytes");
        checkCancelled(cancelled);return new InstallManifest(records,tags);
    }
    private static final class Reader {
        private final byte[] data;
        private final int maxNameBytes;
        private final BooleanSupplier cancelled;
        private int offset;
        Reader(byte[] data,int maxNameBytes,BooleanSupplier cancelled){this.data=data;this.maxNameBytes=maxNameBytes;this.cancelled=cancelled;}
        int remaining(){return data.length-offset;}
        int u8()throws InstallManifestException {require(1);return data[offset++]&255;}
        int u16()throws InstallManifestException{return (u8()<<8)|u8();}
        long u32()throws InstallManifestException{return ((long)u16()<<16)|u16();}
        byte[] bytes(int count)throws InstallManifestException {require(count);byte[] result=Arrays.copyOfRange(data,offset,offset+count);offset+=count;return result;}
        void require(int count)throws InstallManifestException {if(count<0 || count>remaining())throw error(MALFORMED,"Truncated IN manifest");}
        String name()throws InstallManifestException {
            int start=offset;
            while(offset<data.length && data[offset]!=0){
                if(offset-start>=maxNameBytes)throw error(LIMIT,"Install manifest name limit exceeded");
                if((offset-start)%8192==0)checkCancelled(cancelled);offset++;
            }
            if(offset==data.length)throw error(MALFORMED,"Unterminated IN manifest name");
            if(offset==start)throw error(MALFORMED,"Empty IN manifest name");
            try{
                String name=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,start,offset-start)).toString();
                offset++;return name;
            }catch(CharacterCodingException ex){throw error(MALFORMED,"Invalid UTF-8 IN manifest name");}
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled)throws InstallManifestException {if(cancelled.getAsBoolean())throw error(CANCELLED,"Install manifest parsing cancelled");}
    private static InstallManifestException error(InstallManifestException.Reason reason,String message){return new InstallManifestException(reason,message);}
}
