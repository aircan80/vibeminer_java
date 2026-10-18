package io.vibeminer.protocol.tact.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class TactConfigParserTest {
    private static final String C="0123456789abcdef0123456789abcdef", E="fedcba9876543210fedcba9876543210";
    private final TactConfigParser parser=new TactConfigParser();
    private final TactConfigParser.Limits limits=new TactConfigParser.Limits(4096,32,512,16,10000,10000);
    private static String fixture(){return "# synthetic build config\nencoding = "+C+" "+E+"\nencoding-size = 120 100\nroot = "+C+"\nunknown-field = one two\n";}
    @Test void verifiesRawBytesAndPreservesImmutableUnknownFields() throws Exception {
        var config=parse(fixture());var bootstrap=config.encodingBootstrap();
        assertEquals(C,bootstrap.contentKey()); assertEquals(E,bootstrap.encodingKey());
        assertEquals(120,bootstrap.decodedSize());assertEquals(100,bootstrap.encodedSize());
        assertEquals(java.util.List.of("one","two"),config.values().get("unknown-field"));
        assertThrows(UnsupportedOperationException.class,()->config.values().put("x",java.util.List.of()));
        assertThrows(UnsupportedOperationException.class,()->config.values().get("root").add("x"));
    }
    @Test void acceptsCrLfWhitespaceEmptyUnknownValueAndUppercaseHashes() throws Exception {
        var config=parse("\t# fixture\r\n\r\nencoding\t=\t"+C.toUpperCase()+"  "+E.toUpperCase()+"\r\nencoding-size = 0\t0\r\nfuture = \r\n");
        assertEquals(C,config.encodingBootstrap().contentKey());assertTrue(config.values().get("future").isEmpty());
    }
    @Test void rawHashVerificationPrecedesNewlineNormalization() throws Exception {
        byte[] raw=fixture().replace("\n","\r\n").getBytes(StandardCharsets.UTF_8);
        failure(TactConfigException.Reason.INTEGRITY,()->parser.parse(raw,hash(fixture().getBytes(StandardCharsets.UTF_8)),limits,()->false));
        assertEquals(hash(raw),parser.parse(raw,hash(raw),limits,()->false).rawMd5());
    }
    @Test void rejectsDuplicateMissingEqualsInvalidKeysAndControlCharacters() throws Exception {
        for(String text:new String[]{"a = 1\na = 1\n","a = 1\na = 2\n","broken\n","a = b = c\n","A = x\n","../x = y\n","x = a\u0000b\n","x = a\rb\n"})
            failure(TactConfigException.Reason.MALFORMED,()->parse(text));
    }
    @Test void rejectsInvalidUtf8AndBomWithoutReplacement() throws Exception {
        byte[] malformed={(byte)0xc3,0x28};
        failure(TactConfigException.Reason.MALFORMED,()->parser.parse(malformed,hash(malformed),limits,()->false));
        failure(TactConfigException.Reason.MALFORMED,()->parse("\ufeff# synthetic\n"));
    }
    @Test void encodingBootstrapRequiresExactlyTwoFullHashesAndTwoSizes() throws Exception {
        for(String value:new String[]{C,C+" "+E+" "+C,"abc "+E,"g".repeat(32)+" "+E})
            failure(TactConfigException.Reason.MALFORMED,()->parse("encoding = "+value+"\nencoding-size = 1 2\n").encodingBootstrap());
        for(String sizes:new String[]{"1","1 2 3","-1 2","+1 2","0x10 2","1.0 2","18446744073709551616 1"})
            failure(TactConfigException.Reason.MALFORMED,()->parse("encoding = "+C+" "+E+"\nencoding-size = "+sizes+"\n").encodingBootstrap());
        failure(TactConfigException.Reason.MISSING_FIELD,()->parse("root = "+C+"\n").encodingBootstrap());
        failure(TactConfigException.Reason.MISSING_FIELD,()->parse("encoding = "+C+" "+E+"\n").encodingBootstrap());
    }
    @Test void appliesBootstrapSizeLimitsWithoutAssumingOtherFieldsAreHashPairs() throws Exception {
        var config=parse("encoding = "+C+" "+E+"\nencoding-size = 10001 1\nroot = text\n");
        failure(TactConfigException.Reason.LIMIT,config::encodingBootstrap);
        failure(TactConfigException.Reason.LIMIT,()->parse("encoding = "+C+" "+E+"\nencoding-size = 1 10001\n").encodingBootstrap());
        assertEquals("text",config.values().get("root").getFirst());
    }
    @Test void directInstallBootstrapHasIndependentKeysAndDecodedEncodedSizes() throws Exception {
        var config=parse(fixture()+"install = "+E+" "+C+"\ninstall-size = 240 231\n");
        assertEquals(new TactConfig.Bootstrap(E,C,240,231),config.installBootstrap());
        assertEquals(new TactConfig.Bootstrap(C,E,120,100),config.encodingBootstrap());
    }
    @Test void ckeyOnlyInstallIsPreservedButDirectBootstrapRequiresEncodingResolution() throws Exception {
        var config=parse("install = "+C+"\ninstall-size = 240\n");
        assertEquals(java.util.List.of(C),config.values().get("install"));
        failure(TactConfigException.Reason.UNSUPPORTED,config::installBootstrap);
    }
    @Test void loneTrailingCarriageReturnIsNotCrLf() throws Exception {
        failure(TactConfigException.Reason.MALFORMED,()->parse("x = y\r"));
    }
    @Test void boundsBytesLinesLineLengthAndValueTokens() throws Exception {
        failure(TactConfigException.Reason.LIMIT,()->parse("#".repeat(4097)));
        failure(TactConfigException.Reason.LIMIT,()->parse("\n".repeat(33)));
        failure(TactConfigException.Reason.LIMIT,()->parse("#".repeat(513)));
        failure(TactConfigException.Reason.LIMIT,()->parse("x = "+"a ".repeat(17)));
        assertEquals(1,parse("#".repeat(512)+"\nx = y\n").values().size());
    }
    @Test void cancellationAndCallerHashValidation() throws Exception {
        byte[] raw=fixture().getBytes(StandardCharsets.UTF_8);
        failure(TactConfigException.Reason.CANCELLED,()->parser.parse(raw,hash(raw),limits,()->true));
        assertThrows(IllegalArgumentException.class,()->parser.parse(raw,"bad",limits,()->false));
        int[] polls={0};failure(TactConfigException.Reason.CANCELLED,()->parser.parse(raw,hash(raw),limits,()->++polls[0]>2));
    }
    private TactConfig parse(String text) throws Exception {byte[] raw=text.getBytes(StandardCharsets.UTF_8);return parser.parse(raw,hash(raw),limits,()->false);}
    private static String hash(byte[] bytes) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));}
    private static void failure(TactConfigException.Reason reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrows(TactConfigException.class,action).reason());}
}
