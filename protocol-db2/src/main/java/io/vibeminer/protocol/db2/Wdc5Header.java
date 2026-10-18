package io.vibeminer.protocol.db2;

import java.util.EnumSet;
import java.util.Set;

/** Observed header metadata only, never a validated table or a schema match. All u32 values use long. */
public record Wdc5Header(long schemaVersion,String schemaString,long recordCount,long fieldCount,
                         long recordSize,long stringTableSize,long tableHash,long layoutHash,
                         long minId,long maxId,long locale,int flags,int idFieldIndex,long totalFieldCount,
                         long packedDataOffset,long lookupColumnCount,long fieldStorageInfoSize,
                         long commonDataSize,long palletDataSize,long sectionCount) {
    /** Caller-provided expected identifiers; agreement does not prove schema compatibility or build membership. */
    public record ExpectedIdentity(long tableHash,long layoutHash,long schemaVersion){
        public ExpectedIdentity {for(long value:new long[]{tableHash,layoutHash,schemaVersion})if(value<0 || value>0xffffffffL)throw new IllegalArgumentException("Identity must be unsigned 32-bit");}
    }
    public enum Capability {
        RECORD_DECODER_UNAVAILABLE, BODY_NOT_VALIDATED, SCHEMA_SEMANTICS_UNVERIFIED,
        EXPECTED_IDENTITY_NOT_SUPPLIED, TABLE_IDENTITY_MISMATCH, LAYOUT_IDENTITY_MISMATCH,
        SCHEMA_VERSION_MISMATCH, SPARSE_REQUIRES_DECODER, MULTIPLE_SECTIONS_REQUIRE_DECODER,
        UNKNOWN_FLAGS, UNSUPPORTED_FLAGS
    }
    /** Independent reasons, always including unavailable row decoding and unvalidated body. */
    public Set<Capability> gate(ExpectedIdentity expected){
        var result=EnumSet.of(Capability.RECORD_DECODER_UNAVAILABLE,Capability.BODY_NOT_VALIDATED,Capability.SCHEMA_SEMANTICS_UNVERIFIED);
        if(expected==null)result.add(Capability.EXPECTED_IDENTITY_NOT_SUPPLIED);
        else {
            if(tableHash!=expected.tableHash())result.add(Capability.TABLE_IDENTITY_MISMATCH);
            if(layoutHash!=expected.layoutHash())result.add(Capability.LAYOUT_IDENTITY_MISMATCH);
            if(schemaVersion!=expected.schemaVersion())result.add(Capability.SCHEMA_VERSION_MISMATCH);
        }
        if((flags&1)!=0)result.add(Capability.SPARSE_REQUIRES_DECODER);
        if(sectionCount>1)result.add(Capability.MULTIPLE_SECTIONS_REQUIRE_DECODER);
        if((flags&~0x1f)!=0)result.add(Capability.UNKNOWN_FLAGS);
        if((flags&8)!=0)result.add(Capability.UNSUPPORTED_FLAGS);
        return Set.copyOf(result);
    }
}
