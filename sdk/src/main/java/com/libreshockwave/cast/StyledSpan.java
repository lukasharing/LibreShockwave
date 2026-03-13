package com.libreshockwave.cast;

/**
 * A styled text run within an XMED text member.
 * Each span covers a character range with its own font, size, style, and color.
 */
public record StyledSpan(
    int startOffset,    // character offset where this span starts
    int endOffset,      // character offset where this span ends
    String fontName,    // font name for this span (from section 0008)
    int fontSize,       // font size in points (from section 0006)
    boolean bold,       // bold flag
    boolean italic,     // italic flag
    boolean underline,  // underline flag
    int colorR,         // text color R (from section 0003/0006)
    int colorG,         // text color G
    int colorB          // text color B
) {}
