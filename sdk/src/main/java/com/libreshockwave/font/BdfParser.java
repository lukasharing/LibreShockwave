package com.libreshockwave.font;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for BDF (Bitmap Distribution Format) font files.
 * Creates BitmapFont objects with exact pixel data from classic Mac bitmap fonts.
 * No outline rasterization needed — pixels come directly from the font resource.
 *
 * BDF spec: https://adobe-type-tools.github.io/font-tech-notes/pdfs/5005.BDF_Spec.pdf
 */
public class BdfParser {

    /**
     * Parse a BDF font from an InputStream and create a BitmapFont.
     * @param is        input stream of BDF text data
     * @param fontName  font name for identification
     * @return BitmapFont, or null if parsing fails
     */
    public static BitmapFont parse(InputStream is, String fontName) {
        if (is == null) return null;
        try {
            return doParse(is, fontName);
        } catch (Exception e) {
            return null;
        }
    }

    private static BitmapFont doParse(InputStream is, String fontName) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

        int fontAscent = 0, fontDescent = 0, pixelSize = 0;

        // Per-glyph data collected during parse
        Map<Integer, GlyphData> glyphs = new HashMap<>();

        String line;
        // Parse header
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("FONT_ASCENT ")) {
                fontAscent = parseInt(line, "FONT_ASCENT ");
            } else if (line.startsWith("FONT_DESCENT ")) {
                fontDescent = parseInt(line, "FONT_DESCENT ");
            } else if (line.startsWith("PIXEL_SIZE ")) {
                pixelSize = parseInt(line, "PIXEL_SIZE ");
            } else if (line.startsWith("STARTCHAR ")) {
                // Parse glyph
                GlyphData glyph = parseGlyph(reader);
                if (glyph != null && glyph.encoding >= 0) {
                    glyphs.put(glyph.encoding, glyph);
                }
            }
        }
        reader.close();

        if (glyphs.isEmpty()) return null;

        int totalHeight = fontAscent + fontDescent;
        if (totalHeight <= 0) totalHeight = pixelSize > 0 ? pixelSize : 12;
        if (fontAscent <= 0) fontAscent = totalHeight;

        // Determine cell dimensions — find max glyph bounding box
        int maxCellW = 1;
        for (var g : glyphs.values()) {
            // Cell width = max of (advance width, bbx width + xOffset)
            int needed = Math.max(g.dwidth, g.bbxW + Math.max(0, g.bbxX));
            maxCellW = Math.max(maxCellW, needed);
        }
        int cellWidth = maxCellW;
        int cellHeight = totalHeight;

        // Build bitmap grid (16 columns x 8 rows = 128 ASCII chars)
        int bitmapWidth = cellWidth * BitmapFont.GRID_COLUMNS;
        int bitmapHeight = cellHeight * BitmapFont.GRID_ROWS;
        int[] argb = new int[bitmapWidth * bitmapHeight];
        int[] charWidths = new int[BitmapFont.NUM_CHARS];

        // Default space width
        for (int i = 0; i < BitmapFont.NUM_CHARS; i++) {
            charWidths[i] = cellWidth / 2; // reasonable default
        }

        Map<Integer, int[]> overflowGlyphs = new HashMap<>();
        Map<Integer, Integer> overflowWidths = new HashMap<>();

        for (var entry : glyphs.entrySet()) {
            int charCode = entry.getKey();
            GlyphData g = entry.getValue();

            if (charCode < BitmapFont.NUM_CHARS) {
                charWidths[charCode] = g.dwidth;
                blitGlyph(g, argb, bitmapWidth, bitmapHeight,
                        cellWidth, cellHeight, charCode, fontAscent);
            } else if (charCode < 256) {
                // Extended chars (128-255) go to overflow
                overflowWidths.put(charCode, g.dwidth);
                int[] cellBuf = new int[cellWidth * cellHeight];
                blitGlyphToCell(g, cellBuf, cellWidth, cellHeight, fontAscent);
                overflowGlyphs.put(charCode, cellBuf);
            }
        }

        int fontSize = pixelSize > 0 ? pixelSize : totalHeight;

        return BitmapFont.create(argb, bitmapWidth, bitmapHeight,
                cellWidth, cellHeight, charWidths, fontName, fontSize,
                fontAscent, totalHeight,
                overflowGlyphs, overflowWidths);
    }

    /**
     * Blit a glyph's bitmap data into the grid at the correct cell position.
     */
    private static void blitGlyph(GlyphData g, int[] argb, int bitmapWidth, int bitmapHeight,
                                   int cellWidth, int cellHeight, int charCode, int fontAscent) {
        int col = charCode % BitmapFont.GRID_COLUMNS;
        int row = charCode / BitmapFont.GRID_COLUMNS;
        int cellX = col * cellWidth;
        int cellY = row * cellHeight;

        // BDF yOffset: distance from baseline to bottom of bbox
        // Baseline is at fontAscent pixels from top of cell
        int glyphTop = cellY + (fontAscent - g.bbxY - g.bbxH);
        int glyphLeft = cellX + g.bbxX;

        for (int by = 0; by < g.bbxH && by < g.rows.length; by++) {
            int py = glyphTop + by;
            if (py < 0 || py >= bitmapHeight) continue;

            long rowBits = g.rows[by];
            for (int bx = 0; bx < g.bbxW; bx++) {
                int px = glyphLeft + bx;
                if (px < 0 || px >= bitmapWidth) continue;

                // BDF: MSB first, left to right
                int bitPos = (g.rowByteWidth * 8 - 1) - bx;
                if (bitPos < 0) continue;
                if ((rowBits & (1L << bitPos)) != 0) {
                    argb[py * bitmapWidth + px] = 0xFF000000; // black pixel
                }
            }
        }
    }

    /**
     * Blit a glyph into a standalone cell buffer (for overflow chars).
     */
    private static void blitGlyphToCell(GlyphData g, int[] cellBuf, int cellWidth, int cellHeight,
                                         int fontAscent) {
        int glyphTop = fontAscent - g.bbxY - g.bbxH;
        int glyphLeft = g.bbxX;

        for (int by = 0; by < g.bbxH && by < g.rows.length; by++) {
            int py = glyphTop + by;
            if (py < 0 || py >= cellHeight) continue;

            long rowBits = g.rows[by];
            for (int bx = 0; bx < g.bbxW; bx++) {
                int px = glyphLeft + bx;
                if (px < 0 || px >= cellWidth) continue;

                int bitPos = (g.rowByteWidth * 8 - 1) - bx;
                if (bitPos < 0) continue;
                if ((rowBits & (1L << bitPos)) != 0) {
                    cellBuf[py * cellWidth + px] = 0xFF000000;
                }
            }
        }
    }

    private static GlyphData parseGlyph(BufferedReader reader) throws Exception {
        GlyphData g = new GlyphData();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("ENCODING ")) {
                g.encoding = parseInt(line, "ENCODING ");
            } else if (line.startsWith("DWIDTH ")) {
                // DWIDTH dx dy — we only care about dx
                String[] parts = line.substring(7).trim().split("\\s+");
                g.dwidth = Integer.parseInt(parts[0]);
            } else if (line.startsWith("BBX ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                g.bbxW = Integer.parseInt(parts[0]);
                g.bbxH = Integer.parseInt(parts[1]);
                g.bbxX = Integer.parseInt(parts[2]);
                g.bbxY = Integer.parseInt(parts[3]);
            } else if (line.equals("BITMAP")) {
                // Read bitmap rows
                g.rows = new long[g.bbxH];
                g.rowByteWidth = (g.bbxW + 7) / 8; // bytes per row
                for (int i = 0; i < g.bbxH; i++) {
                    String hexLine = reader.readLine();
                    if (hexLine == null) break;
                    hexLine = hexLine.trim();
                    if (hexLine.isEmpty()) { i--; continue; }
                    g.rows[i] = Long.parseUnsignedLong(hexLine, 16);
                }
            } else if (line.equals("ENDCHAR")) {
                return g;
            }
        }
        return g;
    }

    private static int parseInt(String line, String prefix) {
        return Integer.parseInt(line.substring(prefix.length()).trim());
    }

    private static class GlyphData {
        int encoding = -1;
        int dwidth;
        int bbxW, bbxH, bbxX, bbxY;
        long[] rows;
        int rowByteWidth;
    }
}
