package io.vibeminer.protocol.tact.blte;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static io.vibeminer.protocol.tact.blte.BlteException.Reason.*;

/**
 * Bounded, in-memory BLTE N/raw and Z/zlib subset. Does not acquire or publish objects.
 * Input must remain unchanged until this call returns. Caller supplies the exact expected
 * decoded size from its verified manifest. Hash arguments, when present, are 16-byte MD5s.
 * EKey authenticates the header when nonzero, otherwise the whole encoded object;
 * CKey authenticates the complete decoded content. Neither is a local SHA-256 object ID.
 */
public final class BlteDecoder {
    public record Limits(int maxEncodedBytes, int maxDecodedBytes, int maxChunks) {
        public Limits {
            if (maxEncodedBytes < 9 || maxDecodedBytes < 0 || maxChunks < 1)
                throw new IllegalArgumentException("Invalid BLTE limits");
        }
    }

    public byte[] decode(byte[] encoded, long expectedDecodedSize, byte[] expectedEncodingKey,
                         byte[] expectedContentKey, Limits limits, BooleanSupplier cancelled) throws BlteException {
        Objects.requireNonNull(encoded); Objects.requireNonNull(limits); Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        validateHash(expectedEncodingKey); validateHash(expectedContentKey);
        if (encoded.length > limits.maxEncodedBytes() || expectedDecodedSize > limits.maxDecodedBytes())
            throw error(LIMIT,"BLTE resource bound exceeded");
        if (expectedDecodedSize < 0) throw new IllegalArgumentException("Expected decoded size must be nonnegative");
        if (encoded.length < 9 || encoded[0]!='B' || encoded[1]!='L' || encoded[2]!='T' || encoded[3]!='E')
            throw error(MALFORMED,"Missing BLTE header or block");
        long header = u32(encoded,4);
        if (header != 0 && (header < 12 || header > encoded.length)) throw error(MALFORMED,"Invalid BLTE header size");
        if (expectedEncodingKey != null) verifyHash(encoded,0,header==0?encoded.length:(int)header,expectedEncodingKey,cancelled);
        int count=1;
        if (header != 0) {
            if (encoded[8]!=15) throw error(UNSUPPORTED,"Unsupported BLTE table format");
            count=((encoded[9]&255)<<16)|((encoded[10]&255)<<8)|(encoded[11]&255);
            if (count==0) throw error(MALFORMED,"Empty chunk table");
            if (count>limits.maxChunks()) throw error(LIMIT,"BLTE chunk limit exceeded");
            if (header != 12L+24L*count) throw error(MALFORMED,"Chunk table does not match header size");
            long inputEnd=header, outputSize=0;
            for(int i=0;i<count;i++) {
                checkCancelled(cancelled);
                long compressed=u32(encoded,12+24*i), decoded=u32(encoded,16+24*i);
                if(compressed<1) throw error(MALFORMED,"Chunk lacks mode byte");
                inputEnd+=compressed; outputSize+=decoded;
                if(inputEnd>encoded.length) throw error(MALFORMED,"Chunk extends beyond encoded object");
                if(outputSize>limits.maxDecodedBytes()) throw error(LIMIT,"Declared decoded size exceeds limit");
            }
            if(inputEnd!=encoded.length) throw error(MALFORMED,"Trailing encoded bytes");
            if(outputSize!=expectedDecodedSize) throw error(MALFORMED,"Declared decoded size mismatch");
        }
        // All table arithmetic has been checked before allocation.
        byte[] output=new byte[(int)expectedDecodedSize];
        int source=header==0?8:(int)header, target=0;
        for(int i=0;i<count;i++) {
            checkCancelled(cancelled);
            int size=header==0?encoded.length-8:(int)u32(encoded,12+24*i);
            int decoded=header==0?output.length:(int)u32(encoded,16+24*i);
            if(header!=0) verifyHash(encoded,source,size,encoded,20+24*i,cancelled);
            decodeBlock(encoded,source,size,output,target,decoded,cancelled);
            source+=size;target+=decoded;
        }
        if(expectedContentKey!=null) verifyHash(output,0,output.length,expectedContentKey,cancelled);
        checkCancelled(cancelled);
        return output;
    }

    private static void decodeBlock(byte[] source,int start,int size,byte[] target,int offset,int expected,
                                    BooleanSupplier cancelled) throws BlteException {
        int mode=source[start]&255;
        if(mode=='N') {
            if(size-1!=expected) throw error(MALFORMED,"Raw chunk size mismatch");
            for(int copied=0;copied<expected;) {
                checkCancelled(cancelled); int n=Math.min(8192,expected-copied);
                System.arraycopy(source,start+1+copied,target,offset+copied,n);copied+=n;
            }
        } else if(mode=='Z') {
            Inflater inflater=new Inflater();
            try {
                inflater.setInput(source,start+1,size-1);
                byte[] scratch=new byte[8192]; int produced=0;
                while(!inflater.finished()) {
                    checkCancelled(cancelled);
                    // One extra byte detects output beyond the promised size without allocating it.
                    int n=inflater.inflate(scratch,0,(int)Math.min(scratch.length,(long)expected-produced+1));
                    if(n>expected-produced) throw error(MALFORMED,"Zlib output exceeds expected size");
                    if(n>0){System.arraycopy(scratch,0,target,offset+produced,n);produced+=n;}
                    else if(!inflater.finished()) throw error(MALFORMED,"Incomplete, dictionary-dependent or stalled zlib stream");
                }
                if(produced!=expected || inflater.getRemaining()!=0) throw error(MALFORMED,"Zlib length mismatch or trailing bytes");
            } catch(DataFormatException ex) { throw error(MALFORMED,"Invalid zlib stream"); }
            finally { inflater.end(); }
        } else throw error(UNSUPPORTED,"Unsupported BLTE block mode: "+mode);
    }
    private static long u32(byte[] data,int at) {
        return ((long)(data[at]&255)<<24)|((long)(data[at+1]&255)<<16)|((long)(data[at+2]&255)<<8)|(data[at+3]&255);
    }
    private static void validateHash(byte[] hash) {
        if(hash!=null && hash.length!=16) throw new IllegalArgumentException("BLTE MD5 must contain 16 bytes");
    }
    private static void verifyHash(byte[] data,int start,int size,byte[] expected,BooleanSupplier cancelled) throws BlteException {
        verifyHash(data,start,size,expected,0,cancelled);
    }
    private static void verifyHash(byte[] data,int start,int size,byte[] expected,int expectedOffset,BooleanSupplier cancelled) throws BlteException {
        MessageDigest md5;
        try { md5=MessageDigest.getInstance("MD5"); } catch(NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
        for(int consumed=0;consumed<size;) {
            checkCancelled(cancelled);int n=Math.min(8192,size-consumed);md5.update(data,start+consumed,n);consumed+=n;
        }
        byte[] actual=md5.digest();int difference=0;
        for(int i=0;i<16;i++)difference|=actual[i]^expected[expectedOffset+i];
        if(difference!=0)throw error(INTEGRITY,"BLTE MD5 mismatch");
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws BlteException {
        if(cancelled.getAsBoolean())throw error(CANCELLED,"BLTE decode cancelled");
    }
    private static BlteException error(BlteException.Reason reason,String message) {return new BlteException(reason,message);}
}
