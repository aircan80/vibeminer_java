package io.vibeminer.protocol.tact.encoding;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EncodingManifestParserTest {
    final EncodingManifestParser parser=new EncodingManifestParser();
    final EncodingManifestParser.Limits limits=new EncodingManifestParser.Limits(16384,8,4096,100,255,10,1024,1024);
    static final int PAGE=1024;
    static final byte[] SPECS={'z',0,'n',0};
    @Test void preservesAllEkeysAcrossPagesAndUnsigned40BitSizes() throws Exception {
        byte[] first=cp(1,0x123456789aL,10,11),second=cp(2,9,12);
        byte[] encoded=fixture(new byte[][]{first,second},new byte[][]{ep(10,0,0xabcdef0123L),ep(11,1,24),ep(12,0,25)},true,"b:{*=z}");
        var manifest=parse(encoded);var content=manifest.findContent(key(1)).orElseThrow();
        assertEquals(0x123456789aL,content.decodedSize());assertEquals(List.of(key(10),key(11)),content.encodingKeys());
        assertEquals(0xabcdef0123L,manifest.findEncoding(key(10)).orElseThrow().encodedSize());
        assertEquals("n",manifest.findEncoding(key(11)).orElseThrow().encodingSpec());
        assertEquals("b:{*=z}",manifest.fileEncodingSpec());assertEquals(2,manifest.contentEntries().size());
        assertTrue(manifest.findContent(key(3)).isEmpty());assertTrue(manifest.findEncoding(key(13)).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->content.encodingKeys().clear());
        assertThrows(UnsupportedOperationException.class,()->manifest.contentEntries().clear());
    }
    @Test void missingEncodingMetadataStaysMissingWithoutDroppingCandidate() throws Exception {
        var manifest=parse(fixture(new byte[][]{cp(1,5,10,11)},new byte[0][],false,""));
        assertEquals(List.of(key(10),key(11)),manifest.findContent(key(1)).orElseThrow().encodingKeys());
        assertTrue(manifest.findEncoding(key(10)).isEmpty());
    }
    @Test void acceptsEmptyTablesAndZeroPaddingOrFinalEkeySentinel() throws Exception {
        assertTrue(parse(fixture(new byte[0][],new byte[0][],false,"")).contentEntries().isEmpty());
        for(boolean marker:new boolean[]{false,true})assertEquals(1,parse(fixture(new byte[][]{cp(1,0,10)},new byte[][]{ep(10,0,0)},marker,"")).encodingEntries().size());
    }
    @Test void verifiesEntirePaddedPagesAndChecksIndexFirstKeys() throws Exception {
        byte[] bytes=sample();int cIndex=22+SPECS.length,cPage=cIndex+32,eIndex=cPage+PAGE,ePage=eIndex+32;
        for(int offset:new int[]{cIndex+16,cPage+50,eIndex+16,ePage+100}){byte[] bad=bytes.clone();bad[offset]^=1;failure(EncodingManifestException.Reason.INTEGRITY,()->parse(bad));}
        byte[] wrongIndex=bytes.clone();wrongIndex[cIndex]=2;
        failure(EncodingManifestException.Reason.MALFORMED,()->parse(wrongIndex));
    }
    @Test void rejectsBadHeaderTruncationUnsupportedVariantAndOverflowingLayout() throws Exception {
        byte[] full=sample();for(int n:new int[]{0,1,2,3,21,22,30,100,full.length-1}){byte[] truncated=Arrays.copyOf(full,n);failure(EncodingManifestException.Reason.MALFORMED,()->parse(truncated));}
        byte[] magic=full.clone();magic[0]='X';failure(EncodingManifestException.Reason.MALFORMED,()->parse(magic));
        for(int offset:new int[]{2,3,4,17}){byte[] bad=full.clone();bad[offset]=2;failure(EncodingManifestException.Reason.UNSUPPORTED,()->parse(bad));}
        for(int offset:new int[]{9,13,18}){byte[] bad=full.clone();ByteBuffer.wrap(bad).putInt(offset,-1);failure(EncodingManifestException.Reason.LIMIT,()->parse(bad));}
        byte[] zeroPage=full.clone();zeroPage[5]=0;zeroPage[6]=0;failure(EncodingManifestException.Reason.MALFORMED,()->parse(zeroPage));
    }
    @Test void rejectsRecordCrossingPageAndNonzeroPaddingEvenWithValidPageHash() throws Exception {
        byte[] hugeCount=sample();int page=22+SPECS.length+32;hugeCount[page]=(byte)255;rehash(hugeCount,true);
        failure(EncodingManifestException.Reason.MALFORMED,()->parse(hugeCount));
        byte[] junk=sample();junk[page+70]=1;rehash(junk,true);failure(EncodingManifestException.Reason.MALFORMED,()->parse(junk));
    }
    @Test void rejectsDuplicateOrUnorderedContentAndEncodingKeysAcrossPages() throws Exception {
        for(int next:new int[]{1,0})failure(EncodingManifestException.Reason.MALFORMED,()->parse(fixture(new byte[][]{cp(1,1,10),cp(next,1,11)},new byte[0][],false,"")));
        failure(EncodingManifestException.Reason.MALFORMED,()->parse(fixture(new byte[0][],new byte[][]{ep(10,0,1),ep(10,0,2)},true,"")));
    }
    @Test void invalidSpecIndexAndMalformedTrailerDoNotBecomeUsableMetadata() throws Exception {
        failure(EncodingManifestException.Reason.MALFORMED,()->parse(fixture(new byte[0][],new byte[][]{ep(10,99,1)},true,"")));
        byte[] bytes=sample();byte[] bad=Arrays.copyOf(bytes,bytes.length+1);bad[bad.length-1]=(byte)0xff;
        failure(EncodingManifestException.Reason.UNSUPPORTED,()->parse(bad));
        byte[] invalidSpec=sample();invalidSpec[22]=(byte)0xff;failure(EncodingManifestException.Reason.UNSUPPORTED,()->parse(invalidSpec));
    }
    @Test void boundsBytesPagesPageSizeRecordCountSpecsAndCancellation() throws Exception {
        failure(EncodingManifestException.Reason.LIMIT,()->parse(new byte[16385]));
        byte[] bytes=sample();byte[] bigPage=bytes.clone();ByteBuffer.wrap(bigPage).putShort(5,(short)5);failure(EncodingManifestException.Reason.LIMIT,()->parse(bigPage));
        var noRecords=new EncodingManifestParser.Limits(16384,8,4096,0,255,10,1024,1024);
        failure(EncodingManifestException.Reason.LIMIT,()->parser.parse(bytes,noRecords,()->false));
        var noSpecs=new EncodingManifestParser.Limits(16384,8,4096,100,255,1,1024,1024);
        failure(EncodingManifestException.Reason.LIMIT,()->parser.parse(bytes,noSpecs,()->false));
        failure(EncodingManifestException.Reason.CANCELLED,()->parser.parse(bytes,limits,()->true));
        int[] polls={0};failure(EncodingManifestException.Reason.CANCELLED,()->parser.parse(bytes,limits,()->++polls[0]>4));
    }
    @Test void cancellationIsPolledWhileScanningLongOpaqueTrailer() throws Exception {
        byte[] bytes=fixture(new byte[0][],new byte[0][],false,"x".repeat(33000));
        var largeText=new EncodingManifestParser.Limits(40000,0,1024,0,255,10,1024,34000);
        int[] polls={0};failure(EncodingManifestException.Reason.CANCELLED,()->parser.parse(bytes,largeText,()->++polls[0]>5));
    }
    @Test void rejectsPrematureEkeySentinelAndUnterminatedSpecTable() throws Exception {
        byte[] bytes=fixture(new byte[0][],new byte[][]{ep(10,0,1),ep(11,0,1)},false,"");
        int page=22+SPECS.length+64;Arrays.fill(bytes,page+25+16,page+25+20,(byte)0xff);rehash(bytes,false);
        failure(EncodingManifestException.Reason.MALFORMED,()->parse(bytes));
        byte[] spec=sample();spec[22+SPECS.length-1]='x';failure(EncodingManifestException.Reason.MALFORMED,()->parse(spec));
    }
    @Test void candidateLimitAndFullLookupKeyAreEnforced() throws Exception {
        byte[] bytes=fixture(new byte[][]{cp(1,5,10,11)},new byte[0][],false,"");
        var oneKey=new EncodingManifestParser.Limits(16384,8,4096,100,1,10,1024,1024);
        failure(EncodingManifestException.Reason.LIMIT,()->parser.parse(bytes,oneKey,()->false));
        var manifest=parse(bytes);assertThrows(IllegalArgumentException.class,()->manifest.findContent("01"));
        assertThrows(IllegalArgumentException.class,()->manifest.findEncoding(null));
        assertEquals(manifest.findContent(key(1)),manifest.findContent(key(1).toUpperCase(Locale.ROOT)));
    }
    EncodingManifest parse(byte[] bytes)throws EncodingManifestException{return parser.parse(bytes,limits,()->false);}
    static void failure(EncodingManifestException.Reason reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrows(EncodingManifestException.class,action).reason());}
    static String key(int value){return HexFormat.of().formatHex(keyBytes(value));}
    static byte[] keyBytes(int value){byte[] key=new byte[16];key[0]=(byte)value;return key;}
    static byte[] sample()throws Exception{return fixture(new byte[][]{cp(1,5,10)},new byte[][]{ep(10,0,4)},true,"");}
    static void u40(DataOutputStream out,long value)throws Exception{for(int shift=32;shift>=0;shift-=8)out.writeByte((int)(value>>>shift));}
    static byte[] cp(int content,long size,int... encodings)throws Exception{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(encodings.length);u40(out,size);out.write(keyBytes(content));for(int encoding:encodings)out.write(keyBytes(encoding));return bytes.toByteArray();}
    static byte[] ep(int key,long index,long size)throws Exception{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.write(keyBytes(key));out.writeInt((int)index);u40(out,size);return bytes.toByteArray();}
    static byte[] fixture(byte[][] contents,byte[][] encodings,boolean marker,String trailer)throws Exception{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBytes("EN");out.writeByte(1);out.writeByte(16);out.writeByte(16);out.writeShort(1);out.writeShort(1);out.writeInt(contents.length);out.writeInt(encodings.length);out.writeByte(0);out.writeInt(SPECS.length);out.write(SPECS);
        writePages(out,contents,false,false);writePages(out,encodings,true,marker);out.write(trailer.getBytes(StandardCharsets.US_ASCII));return bytes.toByteArray();
    }
    static void writePages(DataOutputStream out,byte[][] records,boolean encoded,boolean marker)throws Exception{
        byte[][] pages=new byte[records.length][];
        for(int i=0;i<records.length;i++){pages[i]=Arrays.copyOf(records[i],PAGE);if(encoded && marker && i==records.length-1)Arrays.fill(pages[i],records[i].length+16,records[i].length+20,(byte)0xff);
            out.write(pages[i],encoded?0:6,16);out.write(MessageDigest.getInstance("MD5").digest(pages[i]));}
        for(byte[] page:pages)out.write(page);
    }
    static void rehash(byte[] input,boolean content)throws Exception{
        var b=ByteBuffer.wrap(input);int ccount=b.getInt(9),ecount=b.getInt(13),spec=b.getInt(18);int index=22+spec,page=index+32*ccount;
        if(!content){index=page+PAGE*ccount;page=index+32*ecount;}
        System.arraycopy(MessageDigest.getInstance("MD5").digest(Arrays.copyOfRange(input,page,page+PAGE)),0,input,index+16,16);
    }
}
