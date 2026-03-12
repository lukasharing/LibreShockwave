package com.libreshockwave.player.cast;

import com.libreshockwave.font.BdfParser;
import com.libreshockwave.font.BitmapFont;

import java.io.InputStream;
import java.util.Map;

/**
 * Embedded Mac system fonts for Director movie compatibility.
 * Director movies authored on Mac reference system fonts (Geneva, Chicago, Monaco, etc.)
 * that aren't available on Windows or in WASM. This bundle provides exact bitmap font
 * data extracted from classic Mac OS (System 6) in BDF format — pixel-perfect, no
 * outline rasterization needed.
 *
 * Fonts are loaded from classpath resources and parsed via BdfParser
 * (pure Java, TeaVM-compatible — no AWT dependency).
 *
 * Source: https://github.com/jcs/classic-mac-fonts
 */
public class MacFontBundle {

    private static final String RESOURCE_BASE = "/fonts/mac/";

    /**
     * Available Mac font BDF resources: "FontName-Size.bdf"
     * Each entry maps a font name (lowercase) to the sizes available.
     */
    private static final Map<String, int[]> AVAILABLE_FONTS = Map.of(
            "geneva",     new int[]{9, 10, 12, 14},
            "chicago",    new int[]{12},
            "monaco",     new int[]{9, 12},
            "helvetica",  new int[]{9, 10, 12, 14},
            "courier",    new int[]{9, 10, 12, 14},
            "times",      new int[]{9, 10, 12, 14}
    );

    /** Proper-case names for BDF filename lookup */
    private static final Map<String, String> FONT_FILE_NAMES = Map.of(
            "geneva", "Geneva",
            "chicago", "Chicago",
            "monaco", "Monaco",
            "helvetica", "Helvetica",
            "courier", "Courier",
            "times", "Times"
    );

    private static volatile boolean initialized = false;

    /**
     * Load all bundled Mac fonts and register them in FontRegistry.
     * Safe to call multiple times — only loads once.
     */
    public static void initialize() {
        if (initialized) return;
        initialized = true;

        for (var entry : AVAILABLE_FONTS.entrySet()) {
            String fontKey = entry.getKey();
            String fileName = FONT_FILE_NAMES.get(fontKey);
            for (int size : entry.getValue()) {
                loadAndRegister(fontKey, fileName, size);
            }
        }
    }

    /**
     * Try to load a specific Mac font at the given size.
     * Returns the BitmapFont if available, null otherwise.
     */
    public static BitmapFont getFont(String fontName, int fontSize) {
        if (fontName == null) return null;
        String key = fontName.toLowerCase();

        int[] sizes = AVAILABLE_FONTS.get(key);
        if (sizes == null) return null;

        // Find best matching size
        int bestSize = findBestSize(sizes, fontSize);
        if (bestSize <= 0) return null;

        // Check if already loaded
        String cacheKey = key + ":" + bestSize;
        BitmapFont cached = FontRegistry.getBitmapFontDirect(cacheKey);
        if (cached != null) return cached;

        // Load on demand
        String fileName = FONT_FILE_NAMES.get(key);
        return loadAndRegister(key, fileName, bestSize);
    }

    /**
     * Check if a font name matches a bundled Mac font.
     */
    public static boolean hasMacFont(String fontName) {
        if (fontName == null) return false;
        return AVAILABLE_FONTS.containsKey(fontName.toLowerCase());
    }

    private static BitmapFont loadAndRegister(String fontKey, String fileName, int size) {
        String resource = RESOURCE_BASE + fileName + "-" + size + ".bdf";
        try (InputStream is = MacFontBundle.class.getResourceAsStream(resource)) {
            if (is == null) return null;
            BitmapFont font = BdfParser.parse(is, fontKey);
            if (font != null) {
                FontRegistry.registerBitmapFont(fontKey, size, font);
            }
            return font;
        } catch (Exception e) {
            return null;
        }
    }

    private static int findBestSize(int[] available, int requested) {
        // Prefer exact match
        for (int s : available) {
            if (s == requested) return s;
        }
        // Find closest size
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int s : available) {
            int diff = Math.abs(s - requested);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }
}
