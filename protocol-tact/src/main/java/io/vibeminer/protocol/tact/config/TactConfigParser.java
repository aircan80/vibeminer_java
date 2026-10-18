package io.vibeminer.protocol.tact.config;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static io.vibeminer.protocol.tact.config.TactConfigException.Reason.*;

/** Pure bounded parser for the supported text-config subset. Hashes exact original bytes. */
public final class TactConfigParser {
    public record Limits(int maxBytes,int maxLines,int maxLineCharacters,int maxTokensPerValue,
                         long maxBootstrapDecodedBytes,long maxBootstrapEncodedBytes){
        public Limits {
            if(maxBytes<1 || maxLines<1 || maxLineCharacters<1 || maxTokensPerValue<1
                    || maxBootstrapDecodedBytes<0 || maxBootstrapEncodedBytes<0)
                throw new IllegalArgumentException("Invalid config bounds");
        }
    }
    /** Expected config key is the raw document MD5, never BLTE header EKey or local SHA-256. */
    public TactConfig parse(byte[] raw,String expectedConfigMd5,Limits limits,BooleanSupplier cancelled) throws TactConfigException {
        Objects.requireNonNull(raw);Objects.requireNonNull(expectedConfigMd5);Objects.requireNonNull(limits);Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if(!TactConfig.isHash(expectedConfigMd5))throw new IllegalArgumentException("Expected config MD5 must contain 32 hex digits");
        if(raw.length>limits.maxBytes())throw error(LIMIT,"Config byte limit exceeded");
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("MD5");}catch(NoSuchAlgorithmException ex){throw new AssertionError(ex);}
        for(int offset=0;offset<raw.length;){checkCancelled(cancelled);int size=Math.min(8192,raw.length-offset);digest.update(raw,offset,size);offset+=size;}
        byte[] actual=digest.digest();
        if(!MessageDigest.isEqual(actual,HexFormat.of().parseHex(expectedConfigMd5)))throw error(INTEGRITY,"Config raw MD5 mismatch");
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}
        catch(CharacterCodingException ex){throw error(MALFORMED,"Config is not valid UTF-8");}
        var values=new LinkedHashMap<String,List<String>>();int start=0,lineCount=0;
        while(start<text.length()){
            checkCancelled(cancelled);
            if(++lineCount>limits.maxLines())throw error(LIMIT,"Config line limit exceeded");
            int end=text.indexOf('\n',start);if(end<0)end=text.length();
            int contentEnd=end;if(end<text.length() && contentEnd>start && text.charAt(contentEnd-1)=='\r')contentEnd--;
            if(contentEnd-start>limits.maxLineCharacters())throw error(LIMIT,"Config line length exceeded");
            String line=text.substring(start,contentEnd);
            for(int i=0;i<line.length();i++){
                char ch=line.charAt(i);
                if((Character.isISOControl(ch) && ch!='\t') || ch=='\ufeff')throw error(MALFORMED,"Unsupported config control character");
            }
            line=line.strip();start=end+1;
            if(line.isEmpty() || line.startsWith("#"))continue;
            int separator=line.indexOf('=');
            if(separator<0 || line.indexOf('=',separator+1)>=0)throw error(MALFORMED,"Config assignment must contain one equals sign");
            String key=line.substring(0,separator).strip(),value=line.substring(separator+1).strip();
            if(!key.matches("[a-z0-9][a-z0-9-]*"))throw error(MALFORMED,"Invalid config field name");
            List<String> tokens=value.isEmpty()?List.of():List.of(value.split("[ \\t]+"));
            if(tokens.size()>limits.maxTokensPerValue())throw error(LIMIT,"Config value token limit exceeded");
            if(values.putIfAbsent(key,tokens)!=null)throw error(MALFORMED,"Duplicate config field: "+key);
        }
        checkCancelled(cancelled);
        return new TactConfig(values,HexFormat.of().formatHex(actual),limits);
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws TactConfigException {if(cancelled.getAsBoolean())throw error(CANCELLED,"Config parsing cancelled");}
    private static TactConfigException error(TactConfigException.Reason reason,String message){return new TactConfigException(reason,message);}
}
