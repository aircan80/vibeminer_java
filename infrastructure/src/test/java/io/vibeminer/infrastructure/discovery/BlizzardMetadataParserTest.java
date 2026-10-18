package io.vibeminer.infrastructure.discovery;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BlizzardMetadataParserTest {
    private final BlizzardMetadataParser parser = new BlizzardMetadataParser();

    @Test void preservesUnknownColumnsAndEmptyFieldsWithReorderedHeaders() {
        var document = parse("Extra!STRING:0|Region!STRING:0|BuildConfig!HEX:16\r\n## seqn = 12\r\n|us|00000000000000000000000000000001\r\n");
        assertEquals("", document.rows().getFirst().get("Extra"));
        assertEquals("us", document.rows().getFirst().get("Region"));
        assertEquals("12", document.sequence().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> document.rows().getFirst().put("x", "y"));
    }

    @Test void preservesSummaryRowsAndFlagsWithoutInterpretingSequenceAsBuild() {
        var document = parse("Product!STRING:0|Seqn!DEC:4|Flags!STRING:0\n## seqn = 42\nwow|10|cdn\nwow|11|\nwow|12|bgdl\nwowf|8|cdn\n");
        assertEquals(4, document.rows().size());
        assertEquals("", document.rows().get(1).get("Flags"));
        assertEquals("8", document.rows().get(3).get("Seqn"));
    }

    @Test void rejectsMalformedHeadersAndRows() {
        for (var text : new String[]{"", "Region\nus\n", "Region!STRING:0|Region!STRING:0\nus|us\n",
                "Region!STRING:0|Build!STRING:0\nus\n", "Region!STRING:0\nus|extra\n"}) {
            assertThrows(MetadataFormatException.class, () -> parse(text), text);
        }
    }

    @Test void rejectsInvalidUtf8InsteadOfReplacingBytes() {
        assertThrows(MetadataFormatException.class, () -> parser.parse(new byte[]{(byte)0xc3, (byte)0x28}));
    }

    @Test void enforcesDocumentAndRowBounds() {
        assertThrows(MetadataFormatException.class, () -> parser.parse(new byte[BlizzardMetadataParser.MAX_DOCUMENT_BYTES + 1]));
        var manyRows = "Region!STRING:0\n" + "us\n".repeat(BlizzardMetadataParser.MAX_ROWS + 1);
        assertThrows(MetadataFormatException.class, () -> parse(manyRows));
    }

    @Test void rejectsConflictingSequenceMetadata() {
        assertThrows(MetadataFormatException.class, () -> parse("Region!STRING:0\n## seqn = 1\n## seqn = 2\nus\n"));
    }

    private MetadataDocument parse(String text) { return parser.parse(text.getBytes(StandardCharsets.UTF_8)); }
}
