package io.vibeminer.protocol.db2;

import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Wdc5HeaderInspectorTest {
    final Wdc5HeaderInspector inspector=new Wdc5HeaderInspector();
    final Wdc5HeaderInspector.Limits limits=new Wdc5HeaderInspector.Limits(4096,1000,100,20,1024,1_000_000);
    // Authored by absolute byte offsets, independently of parser read order.
    static byte[] fixture(){
        byte[] data=new byte[204];data[0]='W';data[1]='D';data[2]='C';data[3]='5';
        var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4,1);data[8]='D';data[9]='e';data[10]='m';data[11]='o';
        b.putInt(136,2);b.putInt(140,3);b.putInt(144,12);b.putInt(148,5);
        b.putInt(152,0xfedcba98);b.putInt(156,0x87654321);b.putInt(160,1);b.putInt(164,2);b.putInt(168,0x10203040);
        b.putShort(172,(short)4);b.putShort(174,(short)0xffff);b.putInt(176,3);b.putInt(180,8);b.putInt(184,1);
        b.putInt(188,72);b.putInt(192,8);b.putInt(196,4);b.putInt(200,1);return data;
    }
    @Test void inspectsActual204ByteLayoutIncludingPost200SectionCount() throws Exception {
        var h=inspect(fixture());assertEquals(204,Wdc5HeaderInspector.HEADER_BYTES);
        assertEquals(1,h.schemaVersion());assertEquals("Demo",h.schemaString());
        assertEquals(2,h.recordCount());assertEquals(3,h.fieldCount());assertEquals(12,h.recordSize());assertEquals(5,h.stringTableSize());
        assertEquals(0xfedcba98L,h.tableHash());assertEquals(0x87654321L,h.layoutHash());assertEquals(0x10203040L,h.locale());
        assertEquals(4,h.flags());assertEquals(65535,h.idFieldIndex());assertEquals(3,h.totalFieldCount());
        assertEquals(8,h.packedDataOffset());assertEquals(1,h.lookupColumnCount());assertEquals(72,h.fieldStorageInfoSize());
        assertEquals(8,h.commonDataSize());assertEquals(4,h.palletDataSize());assertEquals(1,h.sectionCount());
    }
    @Test void rejectsEveryMandatoryHeaderTruncationIncluding200To203() {
        byte[] full=fixture();for(int i=0;i<204;i++){byte[] part=Arrays.copyOf(full,i);failure(Db2HeaderException.Reason.MALFORMED,()->inspect(part));}
    }
    @Test void unknownMagicIsUnsupportedRatherThanAnEmptyTable() {
        byte[] other=fixture();other[3]='3';failure(Db2HeaderException.Reason.UNSUPPORTED,()->inspect(other));
    }
    @Test void boundsUnsignedCountsAndSizesBeforeAnyBodyAllocation() {
        for(int at:new int[]{136,140,144,148,176,180,184,188,192,196,200}){
            byte[] data=fixture();ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(at,-1);
            failure(Db2HeaderException.Reason.LIMIT,()->inspect(data));
        }
        failure(Db2HeaderException.Reason.LIMIT,()->inspect(new byte[4097]));
    }
    @Test void preservesUnsignedIdRangeAndUnknownSchemaForHonestCapabilityGate() throws Exception {
        byte[] data=fixture();var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);b.putInt(4,-1);b.putInt(160,0x80000000);b.putInt(164,-1);
        var h=inspect(data);assertEquals(0xffffffffL,h.schemaVersion());assertEquals(0x80000000L,h.minId());assertEquals(0xffffffffL,h.maxId());
        assertTrue(h.gate(new Wdc5Header.ExpectedIdentity(0xfedcba98L,0x87654321L,1)).contains(Wdc5Header.Capability.SCHEMA_VERSION_MISMATCH));
    }
    @Test void neverClaimsRecordSupportEvenWhenIdentitiesMatch() throws Exception {
        var capabilities=inspect(fixture()).gate(new Wdc5Header.ExpectedIdentity(0xfedcba98L,0x87654321L,1));
        assertTrue(capabilities.contains(Wdc5Header.Capability.RECORD_DECODER_UNAVAILABLE));
        assertTrue(capabilities.contains(Wdc5Header.Capability.BODY_NOT_VALIDATED));
        assertFalse(capabilities.contains(Wdc5Header.Capability.TABLE_IDENTITY_MISMATCH));
        assertThrows(UnsupportedOperationException.class,()->capabilities.clear());
    }
    @Test void capabilityGateDistinguishesIdentitiesSparseSectionsAndUnknownFlags() throws Exception {
        byte[] data=fixture();var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);b.putShort(172,(short)0x8001);b.putInt(200,2);
        var caps=inspect(data).gate(new Wdc5Header.ExpectedIdentity(1,2,3));
        assertTrue(caps.containsAll(Set.of(Wdc5Header.Capability.TABLE_IDENTITY_MISMATCH,Wdc5Header.Capability.LAYOUT_IDENTITY_MISMATCH,
                Wdc5Header.Capability.SCHEMA_VERSION_MISMATCH,Wdc5Header.Capability.SPARSE_REQUIRES_DECODER,
                Wdc5Header.Capability.MULTIPLE_SECTIONS_REQUIRE_DECODER,Wdc5Header.Capability.UNKNOWN_FLAGS)));
    }
    @Test void validatesSchemaStringUtf8AndZeroPadding() throws Exception {
        byte[] data=fixture();data[8]=(byte)0xff;byte[] invalidUtf8=data;failure(Db2HeaderException.Reason.MALFORMED,()->inspect(invalidUtf8));
        data=fixture();data[20]='X';byte[] badPadding=data;failure(Db2HeaderException.Reason.MALFORMED,()->inspect(badPadding));
        data=fixture();Arrays.fill(data,8,136,(byte)'x');assertEquals(128,inspect(data).schemaString().length());
    }
    @Test void cancellationAndInvalidCallerBoundsAreExplicit() {
        failure(Db2HeaderException.Reason.CANCELLED,()->inspector.inspect(new byte[0],limits,()->true));
        assertThrows(IllegalArgumentException.class,()->new Wdc5HeaderInspector.Limits(203,1,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new Wdc5Header.ExpectedIdentity(-1,0,0));
    }
    @Test void absentExpectedIdentityAndReservedFlagRemainExplicitlyUnverified() throws Exception {
        byte[] data=fixture();ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(172,(short)8);
        var caps=inspect(data).gate(null);
        assertTrue(caps.contains(Wdc5Header.Capability.EXPECTED_IDENTITY_NOT_SUPPLIED));
        assertTrue(caps.contains(Wdc5Header.Capability.UNSUPPORTED_FLAGS));
        assertFalse(caps.contains(Wdc5Header.Capability.TABLE_IDENTITY_MISMATCH));
        int[] polls={0};failure(Db2HeaderException.Reason.CANCELLED,()->inspector.inspect(data,limits,()->++polls[0]>1));
    }
    @Test void headerOnlyInspectionDoesNotValidateOrRequireBodyBytes() throws Exception {
        byte[] full=Arrays.copyOf(fixture(),300);Arrays.fill(full,204,full.length,(byte)0xff);
        assertEquals(inspect(fixture()),inspect(full));
        byte[] data=fixture();ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(180,8192);
        assertEquals(8192,inspect(data).packedDataOffset());
    }
    Wdc5Header inspect(byte[] bytes)throws Db2HeaderException{return inspector.inspect(bytes,limits,()->false);}
    static void failure(Db2HeaderException.Reason reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrows(Db2HeaderException.class,action).reason());}
}
