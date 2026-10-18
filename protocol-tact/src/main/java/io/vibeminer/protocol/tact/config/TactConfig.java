package io.vibeminer.protocol.tact.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.vibeminer.protocol.tact.config.TactConfigException.Reason.*;

/** Immutable parsed fields. Unknown keys are preserved without inferred hash semantics. */
public final class TactConfig {
    public record Bootstrap(String contentKey,String encodingKey,long decodedSize,long encodedSize) {}
    private final Map<String,List<String>> values;
    private final String rawMd5;
    private final TactConfigParser.Limits limits;
    TactConfig(Map<String,List<String>> values,String rawMd5,TactConfigParser.Limits limits){
        var copy=new LinkedHashMap<String,List<String>>();values.forEach((key,value)->copy.put(key,List.copyOf(value)));
        this.values=java.util.Collections.unmodifiableMap(copy);this.rawMd5=rawMd5;this.limits=limits;
    }
    public Map<String,List<String>> values(){return values;}
    public String rawMd5(){return rawMd5;}
    public Bootstrap encodingBootstrap() throws TactConfigException{return bootstrap("encoding",false);}
    /** Direct install lookup is supported only when the config supplies both keys and sizes. */
    public Bootstrap installBootstrap() throws TactConfigException{return bootstrap("install",true);}
    private Bootstrap bootstrap(String field,boolean singleKeyIsValid) throws TactConfigException {
        List<String> keys=required(field),sizes=required(field+"-size");
        if(singleKeyIsValid && keys.size()==1 && isHash(keys.getFirst()))
            throw new TactConfigException(UNSUPPORTED,"Install CKey requires encoding-table resolution");
        if(keys.size()!=2 || !isHash(keys.get(0)) || !isHash(keys.get(1)))
            throw new TactConfigException(MALFORMED,"Bootstrap requires full CKey and EKey MD5 values");
        if(sizes.size()!=2)throw new TactConfigException(MALFORMED,"Bootstrap requires decoded and encoded sizes");
        long decoded=size(sizes.get(0)),encoded=size(sizes.get(1));
        if(decoded>limits.maxBootstrapDecodedBytes() || encoded>limits.maxBootstrapEncodedBytes())
            throw new TactConfigException(LIMIT,"Bootstrap size exceeds configured acquisition bounds");
        return new Bootstrap(keys.get(0).toLowerCase(Locale.ROOT),keys.get(1).toLowerCase(Locale.ROOT),decoded,encoded);
    }
    private List<String> required(String field) throws TactConfigException {
        var value=values.get(field);if(value==null)throw new TactConfigException(MISSING_FIELD,"Missing config field: "+field);return value;
    }
    static boolean isHash(String value){return value.matches("[0-9a-fA-F]{32}");}
    private static long size(String value) throws TactConfigException {
        if(!value.matches("[0-9]+"))throw new TactConfigException(MALFORMED,"Invalid decimal bootstrap size");
        try{return Long.parseLong(value);}catch(NumberFormatException ex){throw new TactConfigException(MALFORMED,"Bootstrap size cannot be represented");}
    }
}
