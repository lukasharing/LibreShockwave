package com.libreshockwave.cast;

/**
 * Parser for Director 7+ Text Asset Xtra XMED chunk data.
 * XMED chunks contain text content for "text" sub-type Xtra members.
 *
 * Format: The data is ASCII-encoded with tagged sections.
 * Control bytes (0x01, 0x02, 0x03) separate sections.
 * Section tags are 4-char ASCII (e.g., "0002" for text, "0008" for font).
 * Text section: after tag "0002", find a 0x00 byte, then "HEX_COUNT,MAC_ROMAN_TEXT" ending with 0x03.
 *
 * Two variants observed:
 * - Short form: tag appears early, text follows directly after null+count
 * - Long form: data starts with "FFFF", sections use 0x02 as field separator,
 *   text section identified by searching for the text content pattern
 */
public class XmedTextParser {

    public record XmedText(String text, String fontName, int fontSize,
                              int colorR, int colorG, int colorB) {}

    /**
     * Check if specificData indicates a "text" sub-type Xtra.
     * Director 7+ Text Asset Xtras have "text" at specificData[4..7].
     */
    public static boolean isTextXtra(byte[] specificData) {
        if (specificData == null || specificData.length < 8) return false;
        return specificData[4] == 't' && specificData[5] == 'e'
            && specificData[6] == 'x' && specificData[7] == 't';
    }

    /**
     * Parse text from XMED chunk data.
     * Returns null if the data cannot be parsed.
     */
    public static XmedText parse(byte[] data) {
        if (data == null || data.length < 10) return null;

        // Convert raw bytes to string for searching (ASCII portion)
        String ascii = toAsciiString(data);

        String text = extractText(data, ascii);
        String fontName = extractFont(data, ascii);
        int fontSize = extractFontSize(data, ascii);
        int[] color = extractColor(data, ascii);

        return new XmedText(text, fontName != null ? fontName : "Geneva", fontSize,
                color[0], color[1], color[2]);
    }

    /**
     * Extract text content from XMED data.
     * Looks for the text section which contains "HEX_COUNT,MAC_ROMAN_TEXT".
     */
    private static String extractText(byte[] data, String ascii) {
        // Strategy 1: Look for "0002" tag as ASCII text in the data
        int tagIdx = ascii.indexOf("0002");
        if (tagIdx >= 0) {
            return extractTextAfterTag(data, tagIdx + 4);
        }

        // Strategy 2: For longer XMED data, search for the text content pattern.
        // The text is typically after a 0x00 byte, in format "HEX_COUNT,TEXT" ending with 0x03.
        // Scan for null byte followed by hex digits, comma, then readable text.
        for (int i = 0; i < data.length - 5; i++) {
            if (data[i] == 0x00) {
                int textResult = tryParseTextAt(data, i + 1);
                if (textResult >= 0) {
                    return extractCountCommaText(data, i + 1);
                }
            }
        }

        return null;
    }

    /**
     * Extract text after a tag position. Scans for null byte then "COUNT,TEXT".
     */
    private static String extractTextAfterTag(byte[] data, int startPos) {
        // Skip any length/header bytes until we find a null byte
        for (int i = startPos; i < data.length - 2; i++) {
            if (data[i] == 0x00) {
                return extractCountCommaText(data, i + 1);
            }
        }
        return null;
    }

    /**
     * Try to parse "HEX_COUNT,TEXT" starting at pos.
     * Returns the comma position if valid, -1 otherwise.
     */
    private static int tryParseTextAt(byte[] data, int pos) {
        // Look for hex digits followed by comma
        int commaIdx = -1;
        for (int i = pos; i < Math.min(pos + 10, data.length); i++) {
            int c = data[i] & 0xFF;
            if (c == ',') {
                commaIdx = i;
                break;
            }
            if (!isHexDigit(c)) return -1;
        }
        if (commaIdx < 0 || commaIdx == pos) return -1;

        // Verify there's readable text after the comma
        if (commaIdx + 1 < data.length) {
            int firstChar = data[commaIdx + 1] & 0xFF;
            if (firstChar >= 0x20 && firstChar != 0x03) {
                return commaIdx;
            }
        }
        return -1;
    }

    /**
     * Extract "HEX_COUNT,MAC_ROMAN_TEXT" from data starting at pos.
     * Text ends at 0x03 control byte or end of data.
     */
    private static String extractCountCommaText(byte[] data, int pos) {
        // Find end of this section (0x03 byte or end of data)
        int end = data.length;
        for (int i = pos; i < data.length; i++) {
            if (data[i] == 0x03) {
                end = i;
                break;
            }
        }

        if (end <= pos) return null;

        // Parse "HEX_COUNT,TEXT"
        byte[] section = new byte[end - pos];
        System.arraycopy(data, pos, section, 0, section.length);

        String raw = new String(section, java.nio.charset.StandardCharsets.ISO_8859_1);
        int commaIdx = raw.indexOf(',');
        if (commaIdx < 0) return null;

        String textPart = raw.substring(commaIdx + 1);
        if (textPart.isEmpty()) return null;

        // Decode Mac Roman characters
        byte[] macBytes = new byte[textPart.length()];
        for (int i = 0; i < textPart.length(); i++) {
            macBytes[i] = (byte) textPart.charAt(i);
        }
        return decodeMacRoman(macBytes);
    }

    /**
     * Extract font name from XMED data.
     * Section "0008" contains font entries in format:
     *   tag(4) + length(8) + count(8) + null + "HEX_FIELD_WIDTH," + name_len_byte + font_name + null_padding
     */
    private static String extractFont(byte[] data, String ascii) {
        int tagIdx = ascii.indexOf("0008");
        if (tagIdx < 0) return null;

        // Skip tag(4) + length(8) + count(8) = 20 chars, then find null byte
        for (int i = tagIdx + 20; i < data.length - 2; i++) {
            if (data[i] == 0x00) {
                // After null: "HEX_FIELD_WIDTH," then length_byte + font_name
                // Find the comma
                for (int j = i + 1; j < Math.min(i + 10, data.length); j++) {
                    if (data[j] == ',') {
                        // Next byte is the name length
                        if (j + 1 < data.length) {
                            int nameLen = data[j + 1] & 0xFF;
                            if (nameLen > 0 && j + 2 + nameLen <= data.length) {
                                String fontName = new String(data, j + 2, nameLen,
                                        java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                                if (!fontName.isEmpty()) {
                                    return fontName;
                                }
                            }
                        }
                        break;
                    }
                }
                break;
            }
        }
        return null;
    }

    /**
     * Extract text color from XMED data.
     * XMED text color may be stored as a tagged section (e.g., "0003" for color).
     * Returns {R, G, B} or {255, 255, 255} as default (white).
     */
    private static int[] extractColor(byte[] data, String ascii) {
        // Look for color tag "0003" which typically contains RGB color data
        int tagIdx = ascii.indexOf("0003");
        if (tagIdx >= 0) {
            // After the tag, look for color data
            for (int i = tagIdx + 4; i < data.length - 6; i++) {
                if (data[i] == 0x00) {
                    // Try to parse "HEX,R,G,B" format or direct bytes
                    String colorStr = extractCountCommaText(data, i + 1);
                    if (colorStr != null && !colorStr.isEmpty()) {
                        // Color might be encoded as comma-separated RGB
                        String[] parts = colorStr.split(",");
                        if (parts.length >= 3) {
                            try {
                                int r = Integer.parseInt(parts[0].trim());
                                int g = Integer.parseInt(parts[1].trim());
                                int b = Integer.parseInt(parts[2].trim());
                                return new int[]{r, g, b};
                            } catch (NumberFormatException e) {
                                // Not numeric
                            }
                        }
                    }
                    break;
                }
            }
        }
        // Default: white text (common for Director text on dark backgrounds)
        return new int[]{255, 255, 255};
    }

    /**
     * Extract font size from XMED section 0006 (per-run style data).
     * The section contains 0x02-delimited hex-encoded values. The font size
     * appears as a single hex digit (e.g., 'C' = 12) after the pattern:
     * "480048" (resolution) → "-1" → "0" → font_size_hex
     */
    private static int extractFontSize(byte[] data, String ascii) {
        int idx0006 = ascii.indexOf("0006");
        if (idx0006 < 0) return 12;

        // Skip header: tag(4) + length(8) + count(8) = 20, then data starts
        int secStart = idx0006 + 20;

        // Search for "480048" pattern followed by font size
        // Pattern: 0x02 "480048" 0x02 "-1" 0x02 "0" 0x02 <fontSize>
        for (int i = secStart; i < data.length - 20; i++) {
            if (data[i] == 0x02 && i + 7 < data.length
                    && data[i+1] == '4' && data[i+2] == '8'
                    && data[i+3] == '0' && data[i+4] == '0'
                    && data[i+5] == '4' && data[i+6] == '8') {
                // Found "480048" — skip to font size field
                // After "480048": 0x02"-1" 0x02"0" 0x02<fontSize>
                // Need to skip 3 0x02-delimited fields to reach fontSize content
                int j = i + 7;
                int fieldCount = 0;
                while (j < data.length && fieldCount < 3) {
                    if (data[j] == 0x02) fieldCount++;
                    j++;
                }
                // j now points to fontSize content
                if (j < data.length) {
                    // Read hex-encoded font size (1-2 hex chars)
                    StringBuilder sizeStr = new StringBuilder();
                    while (j < data.length && data[j] != 0x01 && data[j] != 0x02
                            && (data[j] & 0xFF) < 0x80) {
                        sizeStr.append((char) data[j]);
                        j++;
                    }
                    if (sizeStr.length() > 0) {
                        try {
                            int size = Integer.parseInt(sizeStr.toString(), 16);
                            if (size >= 6 && size <= 36) return size;
                        } catch (NumberFormatException e) {
                            // Not valid hex
                        }
                    }
                }
                break;
            }
        }
        return 12; // default
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    /**
     * Convert byte array to ASCII string, replacing non-printable bytes with '.'.
     */
    private static String toAsciiString(byte[] data) {
        char[] chars = new char[data.length];
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            chars[i] = (b >= 0x20 && b < 0x7F) ? (char) b : '.';
        }
        return new String(chars);
    }

    private static String decodeMacRoman(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c < 128) {
                sb.append((char) c);
            } else {
                sb.append(MAC_ROMAN[c - 128]);
            }
        }
        return sb.toString();
    }

    private static final char[] MAC_ROMAN = {
        '\u00C4', '\u00C5', '\u00C7', '\u00C9', '\u00D1', '\u00D6', '\u00DC', '\u00E1',
        '\u00E0', '\u00E2', '\u00E4', '\u00E3', '\u00E5', '\u00E7', '\u00E9', '\u00E8',
        '\u00EA', '\u00EB', '\u00ED', '\u00EC', '\u00EE', '\u00EF', '\u00F1', '\u00F3',
        '\u00F2', '\u00F4', '\u00F6', '\u00F5', '\u00FA', '\u00F9', '\u00FB', '\u00FC',
        '\u2020', '\u00B0', '\u00A2', '\u00A3', '\u00A7', '\u2022', '\u00B6', '\u00DF',
        '\u00AE', '\u00A9', '\u2122', '\u00B4', '\u00A8', '\u2260', '\u00C6', '\u00D8',
        '\u221E', '\u00B1', '\u2264', '\u2265', '\u00A5', '\u00B5', '\u2202', '\u2211',
        '\u220F', '\u03C0', '\u222B', '\u00AA', '\u00BA', '\u03A9', '\u00E6', '\u00F8',
        '\u00BF', '\u00A1', '\u00AC', '\u221A', '\u0192', '\u2248', '\u2206', '\u00AB',
        '\u00BB', '\u2026', '\u00A0', '\u00C0', '\u00C3', '\u00D5', '\u0152', '\u0153',
        '\u2013', '\u2014', '\u201C', '\u201D', '\u2018', '\u2019', '\u00F7', '\u25CA',
        '\u00FF', '\u0178', '\u2044', '\u20AC', '\u2039', '\u203A', '\uFB01', '\uFB02',
        '\u2021', '\u00B7', '\u201A', '\u201E', '\u2030', '\u00C2', '\u00CA', '\u00C1',
        '\u00CB', '\u00C8', '\u00CD', '\u00CE', '\u00CF', '\u00CC', '\u00D3', '\u00D4',
        '\uF8FF', '\u00D2', '\u00DA', '\u00DB', '\u00D9', '\u0131', '\u02C6', '\u02DC',
        '\u00AF', '\u02D8', '\u02D9', '\u02DA', '\u00B8', '\u02DD', '\u02DB', '\u02C7',
    };
}
