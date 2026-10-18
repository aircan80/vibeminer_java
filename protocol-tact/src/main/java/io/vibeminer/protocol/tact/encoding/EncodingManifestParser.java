package io.vibeminer.protocol.tact.encoding;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static io.vibeminer.protocol.tact.encoding.EncodingManifestException.Reason.*;

/** Pure bounded full EN-v1 parser, restricted to 16-byte keys and ASCII ESpec metadata. */
public final class EncodingManifestParser {
    public record Limits(int maxBytes,int maxPages,int maxPageBytes,int maxRecords,int maxKeysPerContent,
                         int maxSpecStrings,int maxSpecBytes,int maxTrailerBytes){
        public Limits {
            if(maxBytes<22 || maxPages<0 || maxPageBytes<1024 || maxRecords<0 || maxKeysPerContent<1 || maxKeysPerContent>255
                    || maxSpecStrings<0 || maxSpecBytes<0 || maxTrailerBytes<0)throw new IllegalArgumentException("Invalid EN parser bounds");
        }
    }
    /** Caller verifies outer decoded CKey; input must remain unchanged during parsing. */
    public EncodingManifest parse(byte[] decoded,Limits limits,BooleanSupplier cancelled)throws EncodingManifestException {
        Objects.requireNonNull(decoded);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if(decoded.length>limits.maxBytes())throw error(LIMIT,"EN input byte limit exceeded");
        if(decoded.length<22)throw error(MALFORMED,"Truncated EN header");
        if(decoded[0]!='E' || decoded[1]!='N')throw error(MALFORMED,"Invalid EN signature");
        if(decoded[2]!=1 || decoded[3]!=16 || decoded[4]!=16 || decoded[17]!=0)throw error(UNSUPPORTED,"Unsupported EN version, key widths or flag");
        int cPageBytes=u16(decoded,5)*1024,ePageBytes=u16(decoded,7)*1024;
        if(cPageBytes==0 || ePageBytes==0)throw error(MALFORMED,"EN page size must be nonzero");
        long cCount=u32(decoded,9),eCount=u32(decoded,13),specBytes=u32(decoded,18);
        if(cPageBytes>limits.maxPageBytes() || ePageBytes>limits.maxPageBytes() || cCount+eCount>limits.maxPages() || specBytes>limits.maxSpecBytes())
            throw error(LIMIT,"EN page or specification limit exceeded");
        long cIndex=22+specBytes,cPages=cIndex+cCount*32,eIndex=cPages+cCount*cPageBytes,ePages=eIndex+eCount*32,end=ePages+eCount*ePageBytes;
        if(end>decoded.length)throw error(MALFORMED,"EN declared layout extends beyond input");
        if(decoded.length-end>limits.maxTrailerBytes())throw error(LIMIT,"EN file ESpec trailer limit exceeded");
        List<String> specs=readSpecs(decoded,22,(int)specBytes,limits,cancelled);
        String trailer=ascii(decoded,(int)end,decoded.length-(int)end,cancelled);
        var context=new RecordBudget(limits.maxRecords());
        var contents=new LinkedHashMap<String,EncodingManifest.ContentEntry>();
        var encodings=new LinkedHashMap<String,EncodingManifest.EncodedEntry>();
        String previous=null;
        for(int page=0;page<cCount;page++){
            int start=(int)cPages+page*cPageBytes,stop=start+cPageBytes,index=(int)cIndex+page*32;
            verifyPage(decoded,start,cPageBytes,index+16,cancelled);
            int offset=start;String first=null;
            while(offset<stop){
                checkCancelled(cancelled);int count=decoded[offset]&255;
                if(count==0){requireZero(decoded,offset,stop,cancelled);break;}
                if(count>limits.maxKeysPerContent())throw error(LIMIT,"EN candidate-key limit exceeded");
                long recordBytes=22L+16L*count;
                if(recordBytes>stop-offset)throw error(MALFORMED,"CKey record crosses page boundary");
                long size=u40(decoded,offset+1);String key=key(decoded,offset+6);
                if(isZeroKey(decoded,offset+6) || (previous!=null && key.compareTo(previous)<=0))throw error(MALFORMED,"CKey records are empty, duplicated or unordered");
                context.record();var keys=new ArrayList<String>(count);
                for(int i=0;i<count;i++){
                    int keyAt=offset+22+i*16;if(isZeroKey(decoded,keyAt))throw error(MALFORMED,"CKey record contains empty EKey");keys.add(key(decoded,keyAt));
                }
                contents.put(key,new EncodingManifest.ContentEntry(key,size,keys));if(first==null)first=key;previous=key;offset+=(int)recordBytes;
            }
            verifyFirstKey(decoded,index,first);
        }
        previous=null;
        for(int page=0;page<eCount;page++){
            int start=(int)ePages+page*ePageBytes,stop=start+ePageBytes,index=(int)eIndex+page*32;
            verifyPage(decoded,start,ePageBytes,index+16,cancelled);
            int offset=start;String first=null;
            while(offset<stop){
                checkCancelled(cancelled);
                if(stop-offset<16){requireZero(decoded,offset,stop,cancelled);break;}
                if(isZeroKey(decoded,offset)){
                    // Normal zero padding, or the writer's final-page zero-key/FFFFFFFF sentinel.
                    int rest=offset+16;
                    if(stop-rest>=4 && u32(decoded,rest)==0xffffffffL){
                        if(page!=eCount-1)throw error(MALFORMED,"EN final marker precedes last EKey page");rest+=4;
                    }
                    requireZero(decoded,rest,stop,cancelled);break;
                }
                if(stop-offset<25)throw error(MALFORMED,"EKey record crosses page boundary");
                String key=key(decoded,offset);long specIndex=u32(decoded,offset+16),size=u40(decoded,offset+20);
                if(previous!=null && key.compareTo(previous)<=0)throw error(MALFORMED,"EKey records are duplicated or unordered");
                if(specIndex>=specs.size())throw error(MALFORMED,"EN ESpec index is unavailable");
                context.record();encodings.put(key,new EncodingManifest.EncodedEntry(key,size,specIndex,specs.get((int)specIndex)));
                if(first==null)first=key;previous=key;offset+=25;
            }
            verifyFirstKey(decoded,index,first);
        }
        checkCancelled(cancelled);return new EncodingManifest(contents,encodings,trailer);
    }
    private static List<String> readSpecs(byte[] data,int start,int size,Limits limits,BooleanSupplier cancelled)throws EncodingManifestException {
        var result=new ArrayList<String>();int from=start,end=start+size;
        // Trailing empty token is retained, matching string-table indices, not a guessed ESpec.
        for(int at=start;at<end;at++){
            if((at-start)%8192==0)checkCancelled(cancelled);
            if(data[at]==0){addSpec(result,ascii(data,from,at-from,cancelled),limits);from=at+1;}
        }
        if(size>0 && from!=end)throw error(MALFORMED,"Unterminated EN ESpec string table");
        if(size>0)addSpec(result,"",limits);
        return List.copyOf(result);
    }
    private static void addSpec(List<String> result,String value,Limits limits)throws EncodingManifestException {
        if(result.size()>=limits.maxSpecStrings())throw error(LIMIT,"EN ESpec string-count limit exceeded");result.add(value);
    }
    private static String ascii(byte[] data,int start,int size,BooleanSupplier cancelled)throws EncodingManifestException {
        for(int i=start;i<start+size;i++){
            if((i-start)%8192==0)checkCancelled(cancelled);
            if((data[i]&255)<32 || (data[i]&255)>126)throw error(UNSUPPORTED,"Unsupported non-printable ASCII EN ESpec metadata");
        }
        return new String(data,start,size,StandardCharsets.US_ASCII);
    }
    private static void verifyPage(byte[] data,int start,int size,int hashOffset,BooleanSupplier cancelled)throws EncodingManifestException {
        MessageDigest md5;try{md5=MessageDigest.getInstance("MD5");}catch(NoSuchAlgorithmException ex){throw new AssertionError(ex);}
        for(int used=0;used<size;){checkCancelled(cancelled);int n=Math.min(8192,size-used);md5.update(data,start+used,n);used+=n;}
        byte[] actual=md5.digest();int difference=0;for(int i=0;i<16;i++)difference|=actual[i]^data[hashOffset+i];
        if(difference!=0)throw error(INTEGRITY,"EN padded-page MD5 mismatch");
    }
    private static void verifyFirstKey(byte[] data,int index,String first)throws EncodingManifestException {
        if(first==null || !key(data,index).equals(first))throw error(MALFORMED,"EN page index does not identify first record");
    }
    private static void requireZero(byte[] data,int from,int to,BooleanSupplier cancelled)throws EncodingManifestException {
        for(int i=from;i<to;i++){if((i-from)%8192==0)checkCancelled(cancelled);if(data[i]!=0)throw error(MALFORMED,"Nonzero EN page padding");}
    }
    private static boolean isZeroKey(byte[] data,int at){int value=0;for(int i=0;i<16;i++)value|=data[at+i];return value==0;}
    private static String key(byte[] data,int at){return HexFormat.of().formatHex(data,at,at+16);}
    private static int u16(byte[] data,int at){return ((data[at]&255)<<8)|(data[at+1]&255);}
    private static long u32(byte[] data,int at){return ((long)u16(data,at)<<16)|u16(data,at+2);}
    private static long u40(byte[] data,int at){return ((long)(data[at]&255)<<32)|u32(data,at+1);}
    private static void checkCancelled(BooleanSupplier cancelled)throws EncodingManifestException {if(cancelled.getAsBoolean())throw error(CANCELLED,"EN parsing cancelled");}
    private static EncodingManifestException error(EncodingManifestException.Reason reason,String message){return new EncodingManifestException(reason,message);}
    private static final class RecordBudget {
        final int maxRecords;int records;
        RecordBudget(int maxRecords){this.maxRecords=maxRecords;}
        void record()throws EncodingManifestException {if(records>=maxRecords)throw error(LIMIT,"EN total record-count limit exceeded");records++;}
    }
}
