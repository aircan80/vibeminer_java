package io.vibeminer.infrastructure.discovery;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.regex.Pattern;

/** Pure bounded parser for Blizzard's pipe-delimited publication metadata. */
public final class BlizzardMetadataParser {
    public static final int MAX_DOCUMENT_BYTES = 1_048_576;
    public static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_LINE_CHARACTERS = 16_384;
    private static final Pattern HEADER = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)![A-Za-z]+:[0-9]+");
    private static final Pattern SEQUENCE = Pattern.compile("##\\s*seqn\\s*=\\s*([0-9]+)\\s*");

    public MetadataDocument parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_DOCUMENT_BYTES) {
            throw new MetadataFormatException("Metadata document size is outside the supported limit");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new MetadataFormatException("Metadata is not valid UTF-8", invalid);
        }
        if (text.indexOf('\0') >= 0) throw new MetadataFormatException("Metadata contains a null character");
        var lines = text.split("\\r?\\n", -1);
        var headers = lines[0].split("\\|", -1);
        if (headers.length > MAX_COLUMNS) throw new MetadataFormatException("Too many metadata columns");
        var columns = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (var header : headers) {
            var matcher = HEADER.matcher(header);
            if (!matcher.matches() || !seen.add(matcher.group(1))) {
                throw new MetadataFormatException("Invalid or duplicate metadata column");
            }
            columns.add(matcher.group(1));
        }
        var rows = new ArrayList<java.util.Map<String, String>>();
        String sequence = null;
        for (int i = 1; i < lines.length; i++) {
            var line = lines[i];
            if (line.length() > MAX_LINE_CHARACTERS) throw new MetadataFormatException("Metadata line exceeds supported limit");
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                var matcher = SEQUENCE.matcher(line);
                if (matcher.matches()) {
                    if (sequence != null && !sequence.equals(matcher.group(1))) {
                        throw new MetadataFormatException("Conflicting metadata sequence values");
                    }
                    sequence = matcher.group(1);
                }
                continue;
            }
            if (rows.size() == MAX_ROWS) throw new MetadataFormatException("Too many metadata rows");
            var values = line.split("\\|", -1);
            if (values.length != columns.size()) throw new MetadataFormatException("Metadata row does not match header columns");
            var row = new LinkedHashMap<String, String>();
            for (int c = 0; c < values.length; c++) row.put(columns.get(c), values[c]);
            rows.add(row);
        }
        return new MetadataDocument(columns, rows, Optional.ofNullable(sequence));
    }
}
