package io.vibeminer.protocol.tact.install;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InstallManifestParserTest {
    final InstallManifestParser parser=new InstallManifestParser();
    final InstallManifestParser.Limits limits=new InstallManifestParser.Limits(8192,100,10,128);
    @Test void parsesBigEndianCountsSizesAndMsbFirstTagMembershipAcrossBytes() throws Exception {
        var manifest=parse(fixture(9,new byte[][]{{(byte)0x81,(byte)0x80},{0x40,0}},new String[]{"Windows","enUS"}));
        assertEquals(9,manifest.entries().size());assertEquals(2,manifest.tags().size());
        assertEquals(0x0102,manifest.tags().getFirst().type());
        assertEquals(0xfedcba98L,manifest.entries().getFirst().decodedSize());
        assertEquals("01010101010101010101010101010101",manifest.entries().getFirst().contentKey());
        assertTrue(manifest.tags().getFirst().includesEntry(0));assertTrue(manifest.tags().getFirst().includesEntry(7));
        assertTrue(manifest.tags().getFirst().includesEntry(8));assertFalse(manifest.tags().getFirst().includesEntry(1));
        assertEquals(List.of(manifest.tags().get(1)),manifest.tagsForEntry(1));
        assertThrows(IndexOutOfBoundsException.class,()->manifest.tagsForEntry(9));
    }
    @Test void preservesUnicodeAndPathLikeNamesAsMetadataWithoutNormalization() throws Exception {
        byte[] bytes=fixture(1,new byte[0][],new String[0]);
        String original="../naïve\\file";bytes=replaceFirstEntryName(bytes,original);
        var manifest=parse(bytes);assertEquals(original,manifest.entries().getFirst().name());
        assertThrows(UnsupportedOperationException.class,()->manifest.entries().clear());
        assertThrows(UnsupportedOperationException.class,()->manifest.tags().clear());
    }
    @Test void supportsEmptyManifestAndIgnoresPaddingBitsOutsideEntryCount() throws Exception {
        assertTrue(parse(fixture(0,new byte[0][],new String[0])).entries().isEmpty());
        var manifest=parse(fixture(1,new byte[][]{{(byte)0xff}},new String[]{"tag"}));
        assertTrue(manifest.tags().getFirst().includesEntry(0));
        assertThrows(IndexOutOfBoundsException.class,()->manifest.tags().getFirst().includesEntry(1));
    }
    @Test void rejectsMagicVersionAndHashLengthOutsideSupportedSubset() throws Exception {
        byte[] data=fixture(1,new byte[0][],new String[0]);
        byte[] magic=data.clone();magic[0]='X';failure(InstallManifestException.Reason.MALFORMED,()->parse(magic));
        for(int offset:new int[]{2,3}){byte[] unsupported=data.clone();unsupported[offset]=2;failure(InstallManifestException.Reason.UNSUPPORTED,()->parse(unsupported));}
    }
    @Test void rejectsEveryTruncationAndTrailingBytes() throws Exception {
        byte[] data=fixture(2,new byte[][]{{(byte)0x80}},new String[]{"tag"});
        for(int i=0;i<data.length;i++){byte[] shortData=Arrays.copyOf(data,i);failure(InstallManifestException.Reason.MALFORMED,()->parse(shortData));}
        failure(InstallManifestException.Reason.MALFORMED,()->parse(Arrays.copyOf(data,data.length+1)));
    }
    @Test void rejectsInvalidUtf8AndUnterminatedOrEmptyNames() throws Exception {
        byte[] invalid=fixture(1,new byte[0][],new String[0]);invalid[10]=(byte)0xff;
        failure(InstallManifestException.Reason.MALFORMED,()->parse(invalid));
        byte[] unterminated=new byte[140];unterminated[0]='I';unterminated[1]='N';unterminated[2]=1;unterminated[3]=16;unterminated[9]=1;Arrays.fill(unterminated,10,140,(byte)'x');
        failure(InstallManifestException.Reason.LIMIT,()->parse(unterminated));
        failure(InstallManifestException.Reason.MALFORMED,()->parse(replaceFirstEntryName(fixture(1,new byte[0][],new String[0]),"")));
    }
    @Test void boundsBytesUnsignedEntryCountsTagsAndNamesBeforeAllocation() throws Exception {
        failure(InstallManifestException.Reason.LIMIT,()->parse(new byte[8193]));
        byte[] data=fixture(0,new byte[0][],new String[0]);Arrays.fill(data,6,10,(byte)0xff);
        failure(InstallManifestException.Reason.LIMIT,()->parse(data));
        byte[] tags=fixture(0,new byte[0][],new String[0]);tags[5]=11;
        failure(InstallManifestException.Reason.LIMIT,()->parse(tags));
        failure(InstallManifestException.Reason.LIMIT,()->parse(replaceFirstEntryName(fixture(1,new byte[0][],new String[0]),"x".repeat(129))));
        assertEquals(128,parse(replaceFirstEntryName(fixture(1,new byte[0][],new String[0]),"x".repeat(128))).entries().getFirst().name().length());
    }
    @Test void cancellationBeforeAndDuringParsingReturnsNoPartialManifest() throws Exception {
        failure(InstallManifestException.Reason.CANCELLED,()->parser.parse(new byte[0],limits,()->true));
        int[] polls={0};byte[] bytes=fixture(9,new byte[][]{{0,0}},new String[]{"tag"});
        failure(InstallManifestException.Reason.CANCELLED,()->parser.parse(bytes,limits,()->++polls[0]>3));
    }
    InstallManifest parse(byte[] bytes)throws InstallManifestException{return parser.parse(bytes,limits,()->false);}
    static void failure(InstallManifestException.Reason reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrows(InstallManifestException.class,action).reason());}
    static byte[] fixture(int count,byte[][] masks,String[] tagNames)throws Exception{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeBytes("IN");out.writeByte(1);out.writeByte(16);out.writeShort(masks.length);out.writeInt(count);
        for(int i=0;i<masks.length;i++){out.write(tagNames[i].getBytes(StandardCharsets.UTF_8));out.writeByte(0);out.writeShort(0x0102+i);out.write(masks[i]);}
        for(int i=0;i<count;i++){out.writeBytes("file"+i);out.writeByte(0);byte[] hash=new byte[16];Arrays.fill(hash,(byte)(i+1));out.write(hash);out.writeInt(0xfedcba98);}
        return bytes.toByteArray();
    }
    static byte[] replaceFirstEntryName(byte[] original,String name)throws Exception{
        var out=new ByteArrayOutputStream();out.write(original,0,10);out.write(name.getBytes(StandardCharsets.UTF_8));out.write(0);out.write(original,16,original.length-16);return out.toByteArray();
    }
}
