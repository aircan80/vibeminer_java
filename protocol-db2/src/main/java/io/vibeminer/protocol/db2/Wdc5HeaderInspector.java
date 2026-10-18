package io.vibeminer.protocol.db2;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static io.vibeminer.protocol.db2.Db2HeaderException.Reason.*;

/** Pure bounded WDC5 mandatory-prefix inspection. Section data, schemas and rows are not parsed. */
public final class Wdc5HeaderInspector {
    // Actual sequential fields end at 204, despite a stale 200-byte constant in a reference reader.
    public static final int HEADER_BYTES=204;
    public record Limits(int maxInputBytes,long maxRecords,long maxFields,long maxSections,
                         long maxRecordBytes,long maxDeclaredBlockBytes){
        public Limits {
            if(maxInputBytes<HEADER_BYTES || maxRecords<0 || maxFields<0 || maxSections<0
                    || maxRecordBytes<0 || maxDeclaredBlockBytes<0)throw new IllegalArgumentException("Invalid WDC5 header bounds");
        }
    }
    /** Input can be just the mandatory prefix. Extra body bytes are neither read nor validated. */
    public Wdc5Header inspect(byte[] input,Limits limits,BooleanSupplier cancelled)throws Db2HeaderException {
        Objects.requireNonNull(input);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if(input.length>limits.maxInputBytes())throw error(LIMIT,"WDC5 input limit exceeded");
        if(input.length<4)throw error(MALFORMED,"Truncated DB2 magic");
        if(input[0]!='W' || input[1]!='D' || input[2]!='C' || input[3]!='5')throw error(UNSUPPORTED,"Unsupported DB2 magic");
        if(input.length<HEADER_BYTES)throw error(MALFORMED,"Truncated WDC5 mandatory header");
        var b=ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        long schema=u32(b,4);String schemaString=readSchemaString(input);
        checkCancelled(cancelled);
        long records=bounded(u32(b,136),limits.maxRecords()),fields=bounded(u32(b,140),limits.maxFields());
        long recordSize=bounded(u32(b,144),limits.maxRecordBytes()),strings=bounded(u32(b,148),limits.maxDeclaredBlockBytes());
        long totalFields=bounded(u32(b,176),limits.maxFields());
        // Observe the packed offset without assuming byte/bit positioning or validating record layout.
        long packed=bounded(u32(b,180),limits.maxDeclaredBlockBytes()),lookup=bounded(u32(b,184),limits.maxFields());
        long storage=bounded(u32(b,188),limits.maxDeclaredBlockBytes());
        long common=bounded(u32(b,192),limits.maxDeclaredBlockBytes()),pallet=bounded(u32(b,196),limits.maxDeclaredBlockBytes());
        long sections=bounded(u32(b,200),limits.maxSections());
        checkCancelled(cancelled);
        return new Wdc5Header(schema,schemaString,records,fields,recordSize,strings,u32(b,152),u32(b,156),u32(b,160),u32(b,164),u32(b,168),
                Short.toUnsignedInt(b.getShort(172)),Short.toUnsignedInt(b.getShort(174)),totalFields,packed,lookup,storage,common,pallet,sections);
    }
    private static String readSchemaString(byte[] input)throws Db2HeaderException {
        int end=136;while(end>8 && input[end-1]==0)end--;
        // A strict supported string subset, not an inference that the schema name identifies the layout.
        for(int i=8;i<end;i++)if(input[i]==0)throw error(MALFORMED,"Non-padding null in WDC5 schema string");
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input,8,end-8)).toString();}
        catch(CharacterCodingException ex){throw error(MALFORMED,"Invalid UTF-8 WDC5 schema string");}
    }
    private static long u32(ByteBuffer b,int offset){return Integer.toUnsignedLong(b.getInt(offset));}
    private static long bounded(long value,long limit)throws Db2HeaderException {if(value>limit)throw error(LIMIT,"WDC5 declared value exceeds header inspection bounds");return value;}
    private static void checkCancelled(BooleanSupplier cancelled)throws Db2HeaderException {if(cancelled.getAsBoolean())throw error(CANCELLED,"WDC5 header inspection cancelled");}
    private static Db2HeaderException error(Db2HeaderException.Reason reason,String message){return new Db2HeaderException(reason,message);}
}
