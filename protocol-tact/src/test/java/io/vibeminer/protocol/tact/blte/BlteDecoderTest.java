package io.vibeminer.protocol.tact.blte;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class BlteDecoderTest {
    private final BlteDecoder decoder = new BlteDecoder();
    private final BlteDecoder.Limits limits = new BlteDecoder.Limits(4096, 4096, 8);

    @Test void decodesZeroHeaderRawAndZlibIncludingEmpty() throws Exception {
        for (byte mode : new byte[]{'N','Z'}) for (byte[] value : new byte[][]{new byte[0], "fixture".getBytes()})
            assertArrayEquals(value, decode(single(block(mode,value)), value.length));
    }
    @Test void decodesMixedTableChunksAndChecksBothIdentities() throws Exception {
        byte[] value = "onetwo".getBytes();
        byte[] encoded = table(new byte[][]{block((byte)'N',"one".getBytes()),block((byte)'Z',"two".getBytes())},new int[]{3,3});
        int header = ByteBuffer.wrap(encoded,4,4).getInt();
        assertArrayEquals(value, decoder.decode(encoded,6,md5(Arrays.copyOf(encoded,header)),md5(value),limits,()->false));
        failure(BlteException.Reason.INTEGRITY,()->decoder.decode(encoded,6,md5(encoded),null,limits,()->false));
        failure(BlteException.Reason.INTEGRITY,()->decoder.decode(encoded,6,null,new byte[16],limits,()->false));
    }
    @Test void zeroHeaderEncodingKeyHashesEntireObject() throws Exception {
        byte[] encoded=single(block((byte)'N',new byte[]{1}));
        assertArrayEquals(new byte[]{1},decoder.decode(encoded,1,md5(encoded),null,limits,()->false));
        failure(BlteException.Reason.INTEGRITY,()->decoder.decode(encoded,1,md5(Arrays.copyOf(encoded,8)),null,limits,()->false));
    }
    @Test void chunkHashIncludesModeAndRejectsCorruption() throws Exception {
        byte[] encoded=table(new byte[][]{block((byte)'N',new byte[]{1})},new int[]{1});
        encoded[encoded.length-1]^=1;
        failure(BlteException.Reason.INTEGRITY,()->decode(encoded,1));
        byte[] modeExcluded=table(new byte[][]{block((byte)'N',new byte[]{1})},new int[]{1});
        System.arraycopy(md5(new byte[]{1}),0,modeExcluded,20,16);
        failure(BlteException.Reason.INTEGRITY,()->decode(modeExcluded,1));
    }
    @Test void rejectsUnsupportedModesWithoutReturningZeroFilledData() throws Exception {
        for (byte mode:new byte[]{'E','F','?'}) failure(BlteException.Reason.UNSUPPORTED,()->decode(single(new byte[]{mode}),0));
    }
    @Test void rejectsBadMagicTruncationHeaderCountAndTrailingTableBytes() throws Exception {
        failure(BlteException.Reason.MALFORMED,()->decode(new byte[8],0));
        failure(BlteException.Reason.MALFORMED,()->decode("BLTE".getBytes(),0));
        byte[] valid=table(new byte[][]{block((byte)'N',new byte[]{1})},new int[]{1});
        failure(BlteException.Reason.MALFORMED,()->decode(Arrays.copyOf(valid,valid.length-1),1));
        failure(BlteException.Reason.MALFORMED,()->decode(Arrays.copyOf(valid,valid.length+1),1));
        byte[] badHeader=valid.clone(); ByteBuffer.wrap(badHeader).putInt(4,37);
        failure(BlteException.Reason.MALFORMED,()->decode(badHeader,1));
        byte[] badCount=valid.clone(); badCount[11]=0;
        failure(BlteException.Reason.MALFORMED,()->decode(badCount,1));
        byte[] unsupportedTable=valid.clone(); unsupportedTable[8]=16;
        failure(BlteException.Reason.UNSUPPORTED,()->decode(unsupportedTable,1));
        byte[] missingMode=valid.clone(); ByteBuffer.wrap(missingMode).putInt(12,0);
        failure(BlteException.Reason.MALFORMED,()->decode(missingMode,1));
        byte[] largeSize=valid.clone(); ByteBuffer.wrap(largeSize).putInt(12,-1);
        failure(BlteException.Reason.MALFORMED,()->decode(largeSize,1));
    }
    @Test void declaredChunkDecodedSizeAndRawLengthMustAgree() throws Exception {
        byte[] badTotal=table(new byte[][]{new byte[]{'N',1}},new int[]{2});
        failure(BlteException.Reason.MALFORMED,()->decode(badTotal,1));
        failure(BlteException.Reason.MALFORMED,()->decode(badTotal,2));
    }
    @Test void exactConfiguredBoundsAreAcceptedAndBadCallerArgumentsRejected() throws Exception {
        byte[] encoded=single(new byte[]{'N',1});
        assertArrayEquals(new byte[]{1},decoder.decode(encoded,1,null,null,new BlteDecoder.Limits(10,1,1),()->false));
        assertThrows(IllegalArgumentException.class,()->decoder.decode(encoded,-1,null,null,limits,()->false));
        assertThrows(IllegalArgumentException.class,()->decoder.decode(encoded,1,new byte[15],null,limits,()->false));
        assertThrows(IllegalArgumentException.class,()->new BlteDecoder.Limits(8,0,1));
        assertThrows(IllegalArgumentException.class,()->new BlteDecoder.Limits(9,-1,1));
        assertThrows(IllegalArgumentException.class,()->new BlteDecoder.Limits(9,0,0));
    }
    @Test void rejectsExactLengthMismatchesAndZlibTrailingOrTruncatedStreams() throws Exception {
        byte[] raw=single(block((byte)'N',new byte[]{1}));
        failure(BlteException.Reason.MALFORMED,()->decode(raw,0));
        failure(BlteException.Reason.MALFORMED,()->decode(raw,2));
        byte[] compressed=block((byte)'Z',"hello".getBytes());
        failure(BlteException.Reason.MALFORMED,()->decode(single(Arrays.copyOf(compressed,compressed.length+1)),5));
        failure(BlteException.Reason.MALFORMED,()->decode(single(Arrays.copyOf(compressed,compressed.length-1)),5));
        failure(BlteException.Reason.MALFORMED,()->decode(single(compressed),4));
        failure(BlteException.Reason.MALFORMED,()->decode(single(compressed),6));
    }
    @Test void boundsZipBombOutputEncodedInputChunksAndUnsignedDeclarations() throws Exception {
        byte[] bomb=single(block((byte)'Z',new byte[8192]));
        failure(BlteException.Reason.LIMIT,()->decode(bomb,8192));
        failure(BlteException.Reason.MALFORMED,()->decode(bomb,1));
        failure(BlteException.Reason.LIMIT,()->decode(new byte[4097],0));
        byte[][] blocks=new byte[9][]; Arrays.setAll(blocks,i->new byte[]{'N'});
        failure(BlteException.Reason.LIMIT,()->decode(table(blocks,new int[9]),0));
        byte[] huge=table(new byte[][]{new byte[]{'N'}},new int[]{-1});
        failure(BlteException.Reason.LIMIT,()->decode(huge,0));
    }
    @Test void observesCancellationBeforeWorkAndDuringInflation() throws Exception {
        failure(BlteException.Reason.CANCELLED,()->decoder.decode(new byte[0],0,null,null,limits,()->true));
        int[] polls={0}; byte[] compressed=single(block((byte)'Z',new byte[3000]));
        failure(BlteException.Reason.CANCELLED,()->decoder.decode(compressed,3000,null,null,limits,()->++polls[0]>2));
    }
    private byte[] decode(byte[] input,int expected) throws BlteException {return decoder.decode(input,expected,null,null,limits,()->false);}
    private static void failure(BlteException.Reason reason, org.junit.jupiter.api.function.Executable work) {
        assertEquals(reason,assertThrows(BlteException.class,work).reason());
    }
    private static byte[] md5(byte[] value) throws Exception {return MessageDigest.getInstance("MD5").digest(value);}
    private static byte[] block(byte mode,byte[] value) throws Exception {
        var out=new ByteArrayOutputStream();out.write(mode);
        if(mode=='Z'){try(var zip=new DeflaterOutputStream(out)){zip.write(value);}}else out.write(value);
        return out.toByteArray();
    }
    private static byte[] single(byte[] block) {return ByteBuffer.allocate(8+block.length).putInt(0x424c5445).putInt(0).put(block).array();}
    private static byte[] table(byte[][] blocks,int[] sizes) throws Exception {
        int header=12+24*blocks.length,total=header; for(byte[] b:blocks)total+=b.length;
        var out=ByteBuffer.allocate(total).putInt(0x424c5445).putInt(header).put((byte)15).put((byte)(blocks.length>>16)).put((byte)(blocks.length>>8)).put((byte)blocks.length);
        for(int i=0;i<blocks.length;i++)out.putInt(blocks[i].length).putInt(sizes[i]).put(md5(blocks[i]));
        for(byte[] b:blocks)out.put(b); return out.array();
    }
}
