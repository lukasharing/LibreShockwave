package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Drawing;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Handles method calls on ImageRef objects.
 * Implements Director's image API: fill, draw, copyPixels, duplicate, etc.
 */
public final class ImageMethodDispatcher {

    private static final int DEFAULT_INVERSE_TEXT_MASK_RGB = 0x7B9498;
    private static Runnable imageMutationCallback;

    private record ResolvedPalette(Palette palette, Datum.CastMemberRef ref, String systemName) {}
    private record MaskOffset(int x, int y) {}

    private ImageMethodDispatcher() {}

    public static void setImageMutationCallback(Runnable callback) {
        imageMutationCallback = callback;
    }

    private static void notifyImageMutation(Bitmap bmp) {
        if (bmp == null) {
            return;
        }
        bmp.clearTextRenderMetadata();
        bmp.markScriptModified();
        if (imageMutationCallback != null) {
            imageMutationCallback.run();
        }
    }

    public static Datum dispatch(Datum.ImageRef imageRef, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        Bitmap bmp = imageRef.bitmap();
        if (bmp == null) {
            return dispatchNullImage(method, args);
        }

        if ("draw".equals(method)) {
            Datum result = draw(bmp, args);
            notifyImageMutation(bmp);
            return result;
        }

        return switch (method) {
            case "fill" -> {
                if (fill(bmp, args)) {
                    notifyImageMutation(bmp);
                }
                yield Datum.VOID;
            }
            case "copypixels" -> {
                Datum result = copyPixels(bmp, args);
                notifyImageMutation(bmp);
                yield result;
            }
            case "setalpha" -> {
                Datum result = setAlpha(bmp, args);
                if (result.isTruthy()) {
                    notifyImageMutation(bmp);
                }
                yield result;
            }
            case "duplicate" -> new Datum.ImageRef(bmp.copy());
            case "crop" -> crop(bmp, args);
            case "setpixel" -> {
                // image.setPixel(x, y, color)
                if (args.size() >= 3) {
                    int px = args.get(0).toInt();
                    int py = args.get(1).toInt();
                    int color = Datum.datumToArgb(args.get(2));
                    if (px >= 0 && px < bmp.getWidth() && py >= 0 && py < bmp.getHeight()) {
                        bmp.setPixel(px, py, color);
                        notifyImageMutation(bmp);
                    }
                }
                yield Datum.VOID;
            }
            case "getpixel" -> {
                // image.getPixel(x, y) → returns color
                if (args.size() >= 2) {
                    int px = args.get(0).toInt();
                    int py = args.get(1).toInt();
                    if (px >= 0 && px < bmp.getWidth() && py >= 0 && py < bmp.getHeight()) {
                        int pixel = bmp.getPixel(px, py);
                        int r = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = pixel & 0xFF;
                        yield new Datum.Color(r, g, b);
                    }
                }
                yield Datum.VOID;
            }
            case "trimwhitespace" -> {
                // Director's trimWhiteSpace() returns a cropped image, not a rect.
                int[] bounds = bmp.trimWhiteSpace();
                if (bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) {
                    // Entirely white - return 1x1 white image
                    Bitmap empty = new Bitmap(1, 1, bmp.getBitDepth());
                    empty.fill(0xFFFFFFFF);
                    yield new Datum.ImageRef(empty);
                }
                Bitmap trimmed = bmp.getRegion(bounds[0], bounds[1],
                        bounds[2] - bounds[0], bounds[3] - bounds[1]);
                trimmed.clearAnchorPoint();
                yield new Datum.ImageRef(trimmed);
            }
            case "creatematte" -> {
                int alphaThreshold = 0;
                if (!args.isEmpty() && !args.get(0).isVoid()) {
                    alphaThreshold = args.get(0).toInt();
                }
                yield new Datum.ImageRef(Drawing.createMatte(bmp, alphaThreshold));
            }
            case "createmask" -> new Datum.ImageRef(Drawing.createMask(bmp));
            case "getat" -> {
                // getAt(index) on image - some scripts use this
                // NOTE: Uses if-else instead of nested switch to avoid TeaVM WASM issue
                // with nested switch expressions using yield.
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                if (index == 1) {
                    yield Datum.of(bmp.getWidth());
                } else if (index == 2) {
                    yield Datum.of(bmp.getHeight());
                } else {
                    yield Datum.VOID;
                }
            }
            default -> Datum.VOID;
        };
    }

    private static Datum dispatchNullImage(String method, List<Datum> args) {
        return switch (method) {
            case "getat" -> {
                if (!args.isEmpty()) {
                    int index = args.get(0).toInt();
                    if (index == 1 || index == 2) {
                        yield Datum.ZERO;
                    }
                }
                yield Datum.VOID;
            }
            case "duplicate", "crop", "trimwhitespace", "creatematte", "createmask" ->
                    new Datum.ImageRef(new Bitmap(0, 0, 32));
            default -> Datum.VOID;
        };
    }

    /**
     * Get a property from an ImageRef.
     */
    public static void setProperty(Datum.ImageRef imageRef, String propName, Datum value) {
        Bitmap bmp = imageRef.bitmap();
        switch (propName.toLowerCase()) {
            case "paletteref" -> {
                ResolvedPalette resolved = resolvePaletteFromDatum(value, bmp);
                if (resolved != null && resolved.palette() != null) {
                    bmp.remapImagePalette(resolved.palette());
                    if (resolved.ref() != null) {
                        Datum.CastMemberRef ref = resolved.ref();
                        bmp.setPaletteRefCastMember(ref.castLibNum(), ref.memberNum());
                    } else if (resolved.systemName() != null) {
                        bmp.setPaletteRefSystemName(resolved.systemName());
                    }
                    notifyImageMutation(bmp);
                }
            }
            case "usealpha" -> {
                bmp.setNativeAlpha(value.isTruthy());
                notifyImageMutation(bmp);
            }
            default -> System.err.println("[LingoVM] Unhandled ImageRef set: " + propName);
        }
    }

    private static ResolvedPalette resolvePaletteFromDatum(Datum value) {
        return resolvePaletteFromDatum(value, null);
    }

    private static ResolvedPalette resolvePaletteFromDatum(Datum value, Bitmap target) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (value instanceof Datum.CastMemberRef ref) {
            if (provider == null) return null;
            Palette palette = provider.getMemberPalette(ref.castLibNum(), ref.memberNum());
            return palette != null ? new ResolvedPalette(palette, ref, null) : null;
        }

        String name = null;
        if (value instanceof Datum.Str str) {
            name = str.value();
        } else if (value instanceof Datum.Symbol sym) {
            name = sym.name();
        }
        if (name == null) {
            return null;
        }

        String normalizedName = Palette.normalizeBuiltInSymbolName(name);
        if (normalizedName != null) {
            Palette palette = Palette.getBuiltInBySymbolName(name);
            if (palette != null) {
                return new ResolvedPalette(palette, null, normalizedName);
            }
        }
        if (provider == null) {
            return null;
        }

        Datum refDatum = provider.getMemberByName(0, name);
        if (refDatum instanceof Datum.CastMemberRef ref) {
            Palette palette = provider.getMemberPalette(ref.castLibNum(), ref.memberNum());
            if (palette != null) {
                return new ResolvedPalette(palette, ref, null);
            }
        }
        Palette palette = provider.resolvePaletteByName(name);
        if (palette != null) {
            return new ResolvedPalette(palette, null, null);
        }
        return null;
    }

    public static Datum getProperty(Datum.ImageRef imageRef, String propName) {
        Bitmap bmp = imageRef.bitmap();
        if (bmp == null) {
            return switch (propName.toLowerCase()) {
                case "rect" -> new Datum.Rect(0, 0, 0, 0);
                case "width", "height", "depth" -> Datum.ZERO;
                case "usealpha" -> Datum.FALSE;
                case "ilk" -> Datum.symbol("image");
                case "image" -> imageRef;
                case "paletteref", "palette" -> Datum.VOID;
                default -> Datum.VOID;
            };
        }
        return switch (propName.toLowerCase()) {
            case "rect" -> new Datum.Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            case "width" -> Datum.of(bmp.getWidth());
            case "height" -> Datum.of(bmp.getHeight());
            case "depth" -> Datum.of(bmp.getBitDepth());
            case "usealpha" -> bmp.isNativeAlpha() ? Datum.TRUE : Datum.FALSE;
            case "ilk" -> Datum.symbol("image");
            case "image" -> imageRef; // Self-reference for .image on an image
            case "paletteref" -> {
                if (bmp.getPaletteRefCastLib() >= 1 && bmp.getPaletteRefMemberNum() >= 1) {
                    yield Datum.CastMemberRef.of(bmp.getPaletteRefCastLib(), bmp.getPaletteRefMemberNum());
                }
                if (bmp.getPaletteRefSystemName() != null) {
                    yield Datum.symbol(bmp.getPaletteRefSystemName());
                }
                yield Datum.VOID;
            }
            default -> Datum.VOID;
        };
    }

    /**
     * image.fill(rect, color) - Fill a rectangular region with a color.
     * In Director: image.fill(destRect, color)
     * Also supports: image.fill(left, top, right, bottom, color)
     */
    private static boolean fill(Bitmap bmp, List<Datum> args) {
        if (args.size() < 2) return false;

        Datum firstArg = args.get(0);

        int left, top, right, bottom;
        Datum colorDatum;

        if (firstArg instanceof Datum.Rect rect) {
            // fill(rect, color)
            left = rect.left();
            top = rect.top();
            right = rect.right();
            bottom = rect.bottom();
            colorDatum = args.get(1);
        } else if (args.size() >= 5) {
            // fill(left, top, right, bottom, color)
            left = args.get(0).toInt();
            top = args.get(1).toInt();
            right = args.get(2).toInt();
            bottom = args.get(3).toInt();
            colorDatum = args.get(4);
        } else {
            return false;
        }

        if (colorDatum instanceof Datum.PropList pl) {
            Datum propColor = getPropIgnoreCase(pl, "color", "Color");
            if (!propColor.isVoid()) {
                colorDatum = propColor;
            }
        }

        // Window overlays may call fill() with VOID when no bgColor is defined.
        // Native Director leaves those pixels untouched, allowing the already
        // rendered backing art to show through transparent elements.
        if (colorDatum.isVoid()) {
            return false;
        }
        // Use bitmap-aware color resolution so paletteIndex() colors resolve
        // through the target bitmap's custom palette (e.g., nav_ui_palette).
        int colorArgb = Datum.datumToArgb(colorDatum, bmp);

        int w = right - left;
        int h = bottom - top;
        if (w > 0 && h > 0) {
            Integer paletteIndex = resolvePaletteIndexFill(colorDatum, bmp);
            if (paletteIndex != null) {
                bmp.fillRectPaletteIndex(left, top, w, h, paletteIndex, colorArgb);
            } else {
                bmp.fillRect(left, top, w, h, colorArgb);
            }
            return true;
        }

        return false;
    }

    private static Integer resolvePaletteIndexFill(Datum colorDatum, Bitmap bmp) {
        if (bmp == null) {
            return null;
        }
        if (colorDatum instanceof Datum.PaletteIndexColor pic) {
            return pic.index() & 0xFF;
        }
        if (bmp.getBitDepth() <= 8 && colorDatum instanceof Datum.Int i) {
            int value = i.value();
            if (value >= 0 && value <= 255) {
                return value;
            }
        }
        return null;
    }

    /**
     * image.draw(rect, propList) - Draw a shape outline.
     * In Director: image.draw(destRect, [#color: color, #shapeType: #rect])
     * Also supports: image.draw(left, top, right, bottom, propList)
     */
    private static Datum draw(Bitmap bmp, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;

        Datum firstArg = args.get(0);

        int left, top, right, bottom;
        Datum propsArg;
        boolean pointToPointLine = false;

        if (firstArg instanceof Datum.Rect rect) {
            // draw(rect, propList)
            left = rect.left();
            top = rect.top();
            right = rect.right();
            bottom = rect.bottom();
            propsArg = args.get(1);
        } else if (firstArg instanceof Datum.Point start
                && args.size() >= 3
                && args.get(1) instanceof Datum.Point end) {
            // draw(pointA, pointB, propList) draws a line in Director.
            left = start.x();
            top = start.y();
            right = end.x();
            bottom = end.y();
            propsArg = args.get(2);
            pointToPointLine = true;
        } else if (args.size() >= 5) {
            // draw(left, top, right, bottom, propList)
            left = args.get(0).toInt();
            top = args.get(1).toInt();
            right = args.get(2).toInt();
            bottom = args.get(3).toInt();
            propsArg = args.get(4);
        } else {
            return Datum.VOID;
        }

        // Extract color from propList
        Datum colorDatum = new Datum.Color(0, 0, 0);
        String shapeType = pointToPointLine ? "line" : "rect";
        int lineSize = 1;

        if (propsArg instanceof Datum.PropList pl) {
            Datum propColor = getPropIgnoreCase(pl, "color", "Color");
            if (!propColor.isVoid()) {
                colorDatum = propColor;
            }
            Datum shapeDatum = getPropIgnoreCase(pl, "shapeType", "shapetype");
            if (shapeDatum instanceof Datum.Symbol s) {
                shapeType = s.name().toLowerCase();
            }
            Datum lineSizeDatum = getPropIgnoreCase(pl, "lineSize", "linesize", "LineSize");
            if (!lineSizeDatum.isVoid()) {
                lineSize = Math.max(0, lineSizeDatum.toInt());
            }
        } else {
            // Second arg is a color directly
            if (!propsArg.isVoid()) {
                colorDatum = propsArg;
            }
        }

        int colorArgb = Datum.datumToArgb(colorDatum, bmp);
        Integer paletteIndex = resolvePaletteIndexFill(colorDatum, bmp);

        if ("rect".equals(shapeType)) {
            int w = right - left;
            int h = bottom - top;
            if (w <= 0 || h <= 0) return Datum.VOID;
            if (lineSize > 0) {
                if (paletteIndex != null) {
                    drawRectOutlineByFill(bmp, left, top, w, h, paletteIndex, colorArgb, lineSize);
                } else {
                    drawRectOutlineByFill(bmp, left, top, w, h, null, colorArgb, lineSize);
                }
            } else {
                if (paletteIndex != null) {
                    Drawing.fillRectPaletteIndex(bmp, left, top, w, h, paletteIndex, colorArgb);
                } else {
                    Drawing.fillRect(bmp, left, top, w, h, colorArgb);
                }
            }
        } else if ("oval".equals(shapeType) || "ellipse".equals(shapeType)) {
            int w = right - left;
            int h = bottom - top;
            if (w <= 0 || h <= 0) return Datum.VOID;
            int cx = left + w / 2;
            int cy = top + h / 2;
            if (lineSize > 0) {
                if (paletteIndex != null) {
                    Drawing.drawEllipsePaletteIndex(bmp, cx, cy, w / 2, h / 2, paletteIndex, colorArgb);
                } else {
                    Drawing.drawEllipse(bmp, cx, cy, w / 2, h / 2, colorArgb);
                }
            } else {
                if (paletteIndex != null) {
                    Drawing.fillEllipsePaletteIndex(bmp, cx, cy, w / 2, h / 2, paletteIndex, colorArgb);
                } else {
                    Drawing.fillEllipse(bmp, cx, cy, w / 2, h / 2, colorArgb);
                }
            }
        } else if ("line".equals(shapeType)) {
            if (left == right && top == bottom) return Datum.VOID;
            if (paletteIndex != null) {
                Drawing.drawLinePaletteIndex(bmp, left, top, right, bottom, paletteIndex, colorArgb);
            } else {
                Drawing.drawLine(bmp, left, top, right, bottom, colorArgb);
            }
        } else {
            int w = right - left;
            int h = bottom - top;
            if (w <= 0 || h <= 0) return Datum.VOID;
            if (paletteIndex != null) {
                Drawing.fillRectPaletteIndex(bmp, left, top, w, h, paletteIndex, colorArgb);
            } else {
                Drawing.fillRect(bmp, left, top, w, h, colorArgb);
            }
        }

        return Datum.VOID;
    }

    private static void drawRectOutlineByFill(Bitmap bmp, int left, int top, int width, int height,
                                              Integer paletteIndex, int colorArgb, int lineSize) {
        if (bmp == null || width <= 0 || height <= 0 || lineSize <= 0) {
            return;
        }
        int stroke = Math.min(lineSize, Math.min(width, height));
        fillDrawnRectEdge(bmp, left, top, width, stroke, paletteIndex, colorArgb);
        if (height > stroke) {
            fillDrawnRectEdge(bmp, left, top + height - stroke, width, stroke, paletteIndex, colorArgb);
        }
        int verticalTop = top + stroke;
        int verticalHeight = height - (stroke * 2);
        if (verticalHeight <= 0) {
            return;
        }
        fillDrawnRectEdge(bmp, left, verticalTop, stroke, verticalHeight, paletteIndex, colorArgb);
        if (width > stroke) {
            fillDrawnRectEdge(bmp, left + width - stroke, verticalTop, stroke, verticalHeight,
                    paletteIndex, colorArgb);
        }
    }

    private static void fillDrawnRectEdge(Bitmap bmp, int x, int y, int width, int height,
                                          Integer paletteIndex, int colorArgb) {
        if (paletteIndex != null) {
            Drawing.fillRectPaletteIndex(bmp, x, y, width, height, paletteIndex, colorArgb);
        } else {
            Drawing.fillRect(bmp, x, y, width, height, colorArgb);
        }
    }

    /**
     * image.setAlpha(alphaLevelOrImage) - Replace the alpha channel on a 32-bit image.
     */
    private static Datum setAlpha(Bitmap bmp, List<Datum> args) {
        if (bmp.getBitDepth() != 32 || args.isEmpty()) {
            return Datum.FALSE;
        }

        Datum alphaArg = args.get(0);
        if (alphaArg instanceof Datum.ImageRef alphaRef) {
            Bitmap alpha = alphaRef.bitmap();
            if (alpha == null
                    || alpha.getBitDepth() != 8
                    || alpha.getWidth() != bmp.getWidth()
                    || alpha.getHeight() != bmp.getHeight()) {
                return Datum.FALSE;
            }

            // Some authored text pipelines build an 8-bit matte with a white
            // outside region and dark glyph pixels. Plain grayscale alpha maps
            // use the opposite polarity, so infer matte-style masks from
            // transparent pixels or from white-backed text matte shapes.
            boolean mattePolarity = hasMattePolarity(alpha);
            for (int y = 0; y < bmp.getHeight(); y++) {
                for (int x = 0; x < bmp.getWidth(); x++) {
                    int alphaPixel = alpha.getPixel(x, y);
                    int alphaLevel = Drawing.maskAlphaFromPixel(alphaPixel);
                    if (mattePolarity) {
                        alphaLevel = 255 - alphaLevel;
                    }
                    int pixel = bmp.getPixel(x, y);
                    bmp.setPixel(x, y, (alphaLevel << 24) | (pixel & 0x00FFFFFF));
                }
            }
            bmp.setNativeAlpha(true);
            return Datum.TRUE;
        }

        int alphaLevel = clamp(alphaArg.toInt(), 0, 255);
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int pixel = bmp.getPixel(x, y);
                bmp.setPixel(x, y, (alphaLevel << 24) | (pixel & 0x00FFFFFF));
            }
        }
        bmp.setNativeAlpha(true);
        return Datum.TRUE;
    }

    private static boolean hasMattePolarity(Bitmap alpha) {
        if (alphaHasTransparency(alpha)) {
            return true;
        }
        return hasWhiteEdgeAndDarkInterior(alpha)
                || hasWhiteCornersAndDarkPixels(alpha);
    }

    private static boolean alphaHasTransparency(Bitmap alpha) {
        for (int y = 0; y < alpha.getHeight(); y++) {
            for (int x = 0; x < alpha.getWidth(); x++) {
                if (((alpha.getPixel(x, y) >>> 24) & 0xFF) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasWhiteEdgeAndDarkInterior(Bitmap alpha) {
        int width = alpha.getWidth();
        int height = alpha.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }

        int edgePixels = 0;
        int whiteEdgePixels = 0;
        boolean hasDarkPixel = false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int luma = Drawing.maskAlphaFromPixel(alpha.getPixel(x, y));
                if (luma < 250) {
                    hasDarkPixel = true;
                }
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    edgePixels++;
                    if (luma >= 250) {
                        whiteEdgePixels++;
                    }
                }
            }
        }

        return hasDarkPixel && edgePixels > 0 && whiteEdgePixels * 4 >= edgePixels * 3;
    }

    private static boolean hasWhiteCornersAndDarkPixels(Bitmap alpha) {
        int width = alpha.getWidth();
        int height = alpha.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }

        int[][] corners = uniqueCorners(width, height);
        int whiteCorners = 0;
        for (int[] corner : corners) {
            int luma = Drawing.maskAlphaFromPixel(alpha.getPixel(corner[0], corner[1]));
            if (luma >= 250) {
                whiteCorners++;
            }
        }
        if (whiteCorners < corners.length) {
            return false;
        }

        boolean hasDarkPixel = false;
        for (int y = 0; y < height && !hasDarkPixel; y++) {
            for (int x = 0; x < width; x++) {
                if (Drawing.maskAlphaFromPixel(alpha.getPixel(x, y)) < 250) {
                    hasDarkPixel = true;
                    break;
                }
            }
        }
        return hasDarkPixel;
    }

    private static int[][] uniqueCorners(int width, int height) {
        if (width == 1 && height == 1) {
            return new int[][] {{0, 0}};
        }
        if (height == 1) {
            return new int[][] {{0, 0}, {width - 1, 0}};
        }
        if (width == 1) {
            return new int[][] {{0, 0}, {0, height - 1}};
        }
        return new int[][] {
                {0, 0},
                {width - 1, 0},
                {0, height - 1},
                {width - 1, height - 1}
        };
    }

    /**
     * image.copyPixels(sourceImage, destRect, srcRect [, propList])
     * Copies pixels from source to this image with optional ink and blend.
     */
    private static Datum copyPixels(Bitmap dest, List<Datum> args) {
        if (args.size() < 3) {
            return Datum.VOID;
        }

        Datum srcDatum = args.get(0);
        if (!(srcDatum instanceof Datum.ImageRef srcRef)) {
            return Datum.VOID;
        }
        Bitmap src = srcRef.bitmap();
        if (src == null) {
            return Datum.VOID;
        }

        Datum destRectDatum = args.get(1);
        Datum srcRectDatum = args.get(2);

        // Handle quad destRect: list of 4 points for perspective/flip transforms
        if (destRectDatum instanceof Datum.List quadList && quadList.items().size() == 4
                && srcRectDatum instanceof Datum.Rect srcRect) {
            return copyPixelsQuad(dest, src, quadList, srcRect, args);
        }

        if (!(destRectDatum instanceof Datum.Rect destRect)) {
            return Datum.VOID;
        }
        if (!(srcRectDatum instanceof Datum.Rect srcRect)) {
            return Datum.VOID;
        }

        // Optional propList with ink, blend, color, bgColor, maskImage
        Palette.InkMode ink = Palette.InkMode.COPY;
        int blend = 255;
        int colorRemap = -1;   // #color param: remap BLACK (foreground) pixels to this color
        int bgColorRemap = -1; // #bgColor param: remap WHITE (background) pixels to this color
        Bitmap mask = null;    // #maskImage param: matte mask for transparency
        MaskOffset maskOffset = new MaskOffset(0, 0);
        ResolvedPalette copyPaletteRef = null; // #paletteRef param: decode/copy source through this palette

        if (args.size() >= 4 && args.get(3) instanceof Datum.PropList pl) {
            // Check for #ink property
            Datum inkDatum = getPropIgnoreCase(pl, "ink", "Ink");
            Palette.InkMode parsedInk = inkFromDatum(inkDatum);
            if (parsedInk != null) {
                ink = parsedInk;
            }
            Datum blendLevelDatum = getPropIgnoreCase(pl, "blendLevel", "blendlevel", "BlendLevel");
            if (!blendLevelDatum.isVoid()) {
                blend = byteToBlendAlpha(blendLevelDatum);
            } else {
                Datum blendDatum = getPropIgnoreCase(pl, "blend", "Blend");
                if (!blendDatum.isVoid()) {
                    blend = percentToBlendAlpha(blendDatum);
                }
            }
            // Check for #color property (foreground color remap)
            // Resolve PaletteIndexColor through source bitmap's palette first, then
            // destination's. The source typically has the content-specific palette
            // (e.g., wall pattern palette) while the destination is a generic canvas.
            Datum colorDatum = getPropIgnoreCase(pl, "color", "Color");
            if (!colorDatum.isVoid()) {
                Bitmap resolveTarget = (colorDatum instanceof Datum.PaletteIndexColor && src.getImagePalette() != null) ? src : dest;
                colorRemap = Datum.datumToArgb(colorDatum, resolveTarget) & 0xFFFFFF;
            }
            // Check for #bgColor property (background color remap)
            Datum bgColorDatum = getPropIgnoreCase(pl, "bgColor", "bgcolor", "BgColor");
            if (!bgColorDatum.isVoid()) {
                Bitmap resolveTarget = (bgColorDatum instanceof Datum.PaletteIndexColor && src.getImagePalette() != null) ? src : dest;
                bgColorRemap = Datum.datumToArgb(bgColorDatum, resolveTarget) & 0xFFFFFF;
            }
            // Check for #maskImage property (matte mask for transparency)
            Datum maskDatum = getPropIgnoreCase(pl, "maskImage", "maskimage", "MaskImage");
            if (maskDatum instanceof Datum.ImageRef maskRef) {
                mask = maskRef.bitmap();
            }
            maskOffset = maskOffsetFromPropList(pl);
            Datum paletteDatum = getPropIgnoreCase(pl, "paletteRef", "paletteref", "PaletteRef");
            if (!paletteDatum.isVoid()) {
                copyPaletteRef = resolvePaletteFromDatum(paletteDatum);
            }
        }

        if (copyPaletteRef != null && copyPaletteRef.palette() != null) {
            src = copyWithPaletteRef(src, copyPaletteRef);
        }

        int srcW = srcRect.right() - srcRect.left();
        int srcH = srcRect.bottom() - srcRect.top();
        int destW = destRect.right() - destRect.left();
        int destH = destRect.bottom() - destRect.top();
        if (dest.getImagePalette() == null && shouldCarryPaletteMetadata(dest, src, ink, blend,
                mask, colorRemap, bgColorRemap)) {
            dest.copyPaletteReferenceFrom(src);
        }
        // Apply #color/#bgColor remapping only for grayscale source bitmaps.
        // Director's copyPixels remap is designed for default black/white text bitmaps
        // (e.g., title text rendered as black-on-white, remapped to white-on-teal).
        // Already-colored bitmaps (e.g., text rendered with explicit txtColor/txtBgColor)
        // must NOT be remapped — doing so destroys their carefully set pixel colors.
        Bitmap effectiveSrc = src;
        int effectiveSrcX = srcRect.left();
        int effectiveSrcY = srcRect.top();
        boolean remapToAlphaMask = false;
        boolean grayscaleColorized = false;
        boolean darkenBgTintCandidate = ink == Palette.InkMode.DARKEN
                && bgColorRemap >= 0
                && colorRemap < 0;
        if ((colorRemap >= 0 || bgColorRemap >= 0)
                && (!src.hasNativeMatteAlpha() || darkenBgTintCandidate)) {
            // Sample source pixels to check if they're grayscale (safe to remap)
            boolean isGrayscale = isMostlyGrayscale(src, srcRect);

            if (isGrayscale) {
                int fgR = colorRemap >= 0 ? (colorRemap >> 16) & 0xFF : 0;
                int fgG = colorRemap >= 0 ? (colorRemap >> 8) & 0xFF : 0;
                int fgB = colorRemap >= 0 ? colorRemap & 0xFF : 0;
                int bgR = bgColorRemap >= 0 ? (bgColorRemap >> 16) & 0xFF : 255;
                int bgG = bgColorRemap >= 0 ? (bgColorRemap >> 8) & 0xFF : 255;
                int bgB = bgColorRemap >= 0 ? bgColorRemap & 0xFF : 255;
                boolean transparentBackground = colorRemap >= 0 && bgColorRemap < 0;
                boolean darkenBgTint = darkenBgTintCandidate;
                boolean indexedDarkenShade = usesIndexedShadeForDarken(src, mask);
                byte[] darkenShadeIndices = indexedDarkenShade ? src.getPaletteIndicesUnsafe() : null;

                effectiveSrc = new Bitmap(srcW, srcH, src.getBitDepth());
                for (int y = 0; y < srcH; y++) {
                    for (int x = 0; x < srcW; x++) {
                        int pixel = src.getPixel(srcRect.left() + x, srcRect.top() + y);
                        int alpha = (pixel >>> 24);
                        int r = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = pixel & 0xFF;
                        int gray = shadeForDarken(src, srcRect.left() + x, srcRect.top() + y, r, g, b,
                                indexedDarkenShade, darkenShadeIndices);
                        if (transparentBackground) {
                            int maskAlpha = (255 - gray) * alpha / 255;
                            int outR = colorRemap >= 0 ? fgR : 0;
                            int outG = colorRemap >= 0 ? fgG : 0;
                            int outB = colorRemap >= 0 ? fgB : 0;
                            effectiveSrc.setPixel(x, y, (maskAlpha << 24) | (outR << 16) | (outG << 8) | outB);
                        } else if (darkenBgTint) {
                            int nr = gray * bgR / 256;
                            int ng = gray * bgG / 256;
                            int nb = gray * bgB / 256;
                            effectiveSrc.setPixel(x, y, (alpha << 24) | (nr << 16) | (ng << 8) | nb);
                        } else {
                            float t = gray / 255.0f;
                            int nr = (int) ((1 - t) * fgR + t * bgR + 0.5f);
                            int ng = (int) ((1 - t) * fgG + t * bgG + 0.5f);
                            int nb = (int) ((1 - t) * fgB + t * bgB + 0.5f);
                            effectiveSrc.setPixel(x, y, (alpha << 24) | (nr << 16) | (ng << 8) | nb);
                        }
                    }
                }
                effectiveSrcX = 0;
                effectiveSrcY = 0;
                remapToAlphaMask = transparentBackground;
                grayscaleColorized = true;
            }
        }

        Datum.Rect effectiveSrcRect = new Datum.Rect(effectiveSrcX, effectiveSrcY,
                effectiveSrcX + srcW, effectiveSrcY + srcH);
        Integer backgroundKeyRgb = ink == Palette.InkMode.BACKGROUND_TRANSPARENT
                ? Integer.valueOf(resolveBackgroundTransparentKey(
                        effectiveSrc, effectiveSrcRect, bgColorRemap, grayscaleColorized))
                : null;

        if (ink == Palette.InkMode.BACKGROUND_TRANSPARENT
                && effectiveSrc.hasNativeMatteAlpha()
                && isInverseWhiteAlphaMask(effectiveSrc, effectiveSrcX, effectiveSrcY, srcW, srcH)) {
            int inverseMaskInkRgb = resolveInverseMaskInkRgb(bgColorRemap);
            effectiveSrc = invertWhiteAlphaMaskToInk(effectiveSrc, effectiveSrcX, effectiveSrcY, srcW, srcH,
                    inverseMaskInkRgb);
            effectiveSrcX = 0;
            effectiveSrcY = 0;
        }

        Palette.InkMode effectiveInk = ink;
        if (remapToAlphaMask) {
            effectiveInk = Palette.InkMode.COPY;
        }
        if (shouldUseImageDefaultMatte(effectiveSrc, effectiveInk)) {
            effectiveSrc = Drawing.applyFloodFillTransparency(effectiveSrc,
                    new Drawing.FloodFillMatte(0xFFFFFF, 0));
            effectiveInk = Palette.InkMode.COPY;
        }
        if (effectiveInk == Palette.InkMode.BACKGROUND_TRANSPARENT
                && src.hasNativeMatteAlpha()
                && !hasOpaqueBackgroundKeyBorder(effectiveSrc, effectiveSrcX, effectiveSrcY, srcW, srcH,
                        backgroundKeyRgb)) {
            effectiveInk = Palette.InkMode.COPY;
        }
        if (effectiveInk == Palette.InkMode.DARKEN) {
            if (!grayscaleColorized) {
                effectiveSrc = multiplyBitmapColorForDarken(effectiveSrc,
                        bgColorRemap >= 0 ? bgColorRemap : 0xFFFFFF,
                        usesIndexedShadeForDarken(effectiveSrc, mask));
                effectiveSrcX = 0;
                effectiveSrcY = 0;
            }
        }
        // Director's copyPixels applies a global blend factor to the copied pixels.
        // With the default COPY ink, blend<100 behaves like a blend operation over
        // the current destination instead of a straight overwrite.
        if (blend < 255 && effectiveInk == Palette.InkMode.COPY) {
            effectiveInk = Palette.InkMode.BLEND;
        }

        if (srcW == destW && srcH == destH) {
            // No scaling needed - direct copy
            boolean preservePaletteIndices = canPreservePaletteIndices(dest, effectiveSrc,
                    effectiveInk, blend, mask, colorRemap, bgColorRemap);
            if (!preservePaletteIndices) {
                clearPaletteIndicesBeforeNonIndexedCopy(dest);
            }
            clearFullTextRerenderBeforeBackgroundTransparentCopy(dest, effectiveSrc,
                    destRect.left(), destRect.top(), effectiveSrcX, effectiveSrcY,
                    srcW, srcH, ink, blend, mask, backgroundKeyRgb);
            Drawing.copyPixels(dest, effectiveSrc,
                    destRect.left(), destRect.top(),
                    effectiveSrcX, effectiveSrcY,
                    srcW, srcH, effectiveInk, blend, mask, backgroundKeyRgb,
                    srcRect.left() - maskOffset.x(), srcRect.top() - maskOffset.y());
            if (preservePaletteIndices) {
                preservePaletteIndicesOnCopy(dest, effectiveSrc,
                        destRect.left(), destRect.top(),
                        effectiveSrcX, effectiveSrcY,
                        srcW, srcH, srcW, srcH,
                        effectiveInk, blend, mask, colorRemap, bgColorRemap, backgroundKeyRgb);
            }
        } else {
            // Scaling needed - create scaled intermediate, applying mask at source coordinates
            Bitmap scaled = new Bitmap(destW, destH, effectiveSrc.getBitDepth());
            scaled.copyPaletteMetadataFrom(effectiveSrc);
            byte[] srcPaletteIndices = effectiveSrc.getPaletteIndicesUnsafe();
            byte[] scaledPaletteIndices = srcPaletteIndices != null
                    && srcPaletteIndices.length >= effectiveSrc.getWidth() * effectiveSrc.getHeight()
                    ? new byte[destW * destH]
                    : null;
            for (int y = 0; y < destH; y++) {
                int sy = effectiveSrcY + (y * srcH / destH);
                for (int x = 0; x < destW; x++) {
                    int sx = effectiveSrcX + (x * srcW / destW);
                    if (sx < 0 || sx >= effectiveSrc.getWidth()
                            || sy < 0 || sy >= effectiveSrc.getHeight()) {
                        continue;
                    }
                    // Check mask at original source coordinates during scaling
                    if (mask != null) {
                        int origSx = srcRect.left() + (x * srcW / destW) - maskOffset.x();
                        int origSy = srcRect.top() + (y * srcH / destH) - maskOffset.y();
                        if (!Drawing.maskAllowsPixel(mask, origSx, origSy)) {
                            continue; // Leave as transparent (default 0)
                        }
                    }
                    scaled.setPixelPreservePaletteIndex(x, y, effectiveSrc.getPixel(sx, sy));
                    if (scaledPaletteIndices != null) {
                        scaledPaletteIndices[y * destW + x] =
                                srcPaletteIndices[sy * effectiveSrc.getWidth() + sx];
                    }
                }
            }
            if (scaledPaletteIndices != null) {
                scaled.setPaletteIndices(scaledPaletteIndices);
            }
            // Mask already applied during scaling, so pass null to Drawing
            boolean preservePaletteIndices = canPreservePaletteIndices(dest, scaled,
                    effectiveInk, blend, null, colorRemap, bgColorRemap);
            if (!preservePaletteIndices) {
                clearPaletteIndicesBeforeNonIndexedCopy(dest);
            }
            clearFullTextRerenderBeforeBackgroundTransparentCopy(dest, scaled,
                    destRect.left(), destRect.top(), 0, 0,
                    destW, destH, ink, blend, null, backgroundKeyRgb);
            Drawing.copyPixels(dest, scaled,
                    destRect.left(), destRect.top(),
                    0, 0, destW, destH, effectiveInk, blend, null, backgroundKeyRgb);
            if (preservePaletteIndices) {
                preservePaletteIndicesOnCopy(dest, scaled,
                        destRect.left(), destRect.top(),
                        0, 0,
                        destW, destH, destW, destH,
                        effectiveInk, blend, null, colorRemap, bgColorRemap, backgroundKeyRgb);
            }
        }

        return Datum.VOID;
    }

    private static boolean shouldUseImageDefaultMatte(Bitmap src, Palette.InkMode ink) {
        return ink == Palette.InkMode.MATTE
                && src != null
                && src.getBitDepth() == 32
                && src.isScriptModified()
                && !src.hasNativeMatteAlpha();
    }

    private static void clearFullTextRerenderBeforeBackgroundTransparentCopy(
            Bitmap dest, Bitmap src,
            int destX, int destY,
            int srcX, int srcY,
            int width, int height,
            Palette.InkMode ink, int blend,
            Bitmap mask,
            Integer backgroundKeyRgb) {
        if (dest == null || src == null
                || ink != Palette.InkMode.BACKGROUND_TRANSPARENT
                || blend < 255
                || mask != null
                || width <= 0 || height <= 0) {
            return;
        }

        int backgroundRgb = textRenderBackgroundRgb(src);
        if (backgroundRgb < 0) {
            return;
        }
        if (backgroundKeyRgb != null && (backgroundKeyRgb & 0xFFFFFF) != backgroundRgb) {
            return;
        }

        if (destX != 0 || destY != 0
                || width != dest.getWidth() || height != dest.getHeight()
                || srcX != 0 || srcY != 0
                || width != src.getWidth() || height != src.getHeight()) {
            return;
        }
        if (!opaqueCornersMatch(dest, 0, 0, width, height, backgroundRgb)) {
            return;
        }

        dest.fillRect(0, 0, width, height, 0xFF000000 | backgroundRgb);
    }

    private static int textRenderBackgroundRgb(Bitmap src) {
        if (src == null || !src.isTextRenderedImage()) {
            return -1;
        }
        return src.getTextRenderBackgroundColor() & 0xFFFFFF;
    }

    private static void clearPaletteIndicesBeforeNonIndexedCopy(Bitmap dest) {
        if (dest != null) {
            dest.clearPaletteIndices();
        }
    }

    private static Bitmap copyWithPaletteRef(Bitmap src, ResolvedPalette paletteRef) {
        Bitmap copy = src.copy();
        copy.remapImagePalette(paletteRef.palette());
        if (paletteRef.ref() != null) {
            Datum.CastMemberRef ref = paletteRef.ref();
            copy.setPaletteRefCastMember(ref.castLibNum(), ref.memberNum());
        } else if (paletteRef.systemName() != null) {
            copy.setPaletteRefSystemName(paletteRef.systemName());
        }
        return copy;
    }

    private static boolean shouldCarryPaletteMetadata(Bitmap dest, Bitmap src,
                                                      Palette.InkMode ink, int blend,
                                                      Bitmap mask,
                                                      int colorRemap, int bgColorRemap) {
        return dest != null
                && src != null
                && canAcceptCopiedPaletteIndices(dest)
                && src.getImagePalette() != null
                && src.getBitDepth() <= 8
                && dest.getBitDepth() <= 8
                && dest.getBitDepth() >= src.getBitDepth()
                && (ink == Palette.InkMode.COPY
                    || ink == Palette.InkMode.MATTE
                    || ink == Palette.InkMode.BACKGROUND_TRANSPARENT)
                && blend >= 255
                && mask == null
                && colorRemap < 0
                && bgColorRemap < 0;
    }

    /**
     * copyPixels with quad destination (list of 4 points).
     * Director uses this for image-space transforms such as flipH/flipV and
     * 90-degree rotations.
     */
    private static Datum copyPixelsQuad(Bitmap dest, Bitmap src, Datum.List quad,
                                         Datum.Rect srcRect, List<Datum> args) {
        // Extract the 4 corner points
        var items = quad.items();
        if (items.size() != 4) return Datum.VOID;

        // Director quad order: [topLeft, topRight, bottomRight, bottomLeft]
        // (confirmed by Scripting Reference: "upper left, upper right, lower right, and lower left")
        int[] px = new int[4], py = new int[4];
        for (int i = 0; i < 4; i++) {
            if (items.get(i) instanceof Datum.Point p) {
                px[i] = p.x();
                py[i] = p.y();
            } else {
                return Datum.VOID;
            }
        }

        int srcW = srcRect.right() - srcRect.left();
        int srcH = srcRect.bottom() - srcRect.top();
        if (srcW <= 0 || srcH <= 0) return Datum.VOID;

        // Determine bounding box of the quad
        int minX = Math.min(Math.min(px[0], px[1]), Math.min(px[2], px[3]));
        int minY = Math.min(Math.min(py[0], py[1]), Math.min(py[2], py[3]));
        int maxX = Math.max(Math.max(px[0], px[1]), Math.max(px[2], px[3]));
        int maxY = Math.max(Math.max(py[0], py[1]), Math.max(py[2], py[3]));
        int destW = maxX - minX;
        int destH = maxY - minY;
        if (destW <= 0 || destH <= 0) return Datum.VOID;

        Datum.PropList props = args.size() > 3 && args.get(3) instanceof Datum.PropList pl ? pl : null;
        Bitmap mask = null;
        MaskOffset maskOffset = new MaskOffset(0, 0);
        if (props != null) {
            Datum maskDatum = getPropIgnoreCase(props, "maskImage", "maskimage", "MaskImage");
            if (maskDatum instanceof Datum.ImageRef maskRef) {
                mask = maskRef.bitmap();
            }
            maskOffset = maskOffsetFromPropList(props);
        }

        // Map Director's quad orientation back into source-space coordinates.
        // This covers identity, flips, and 90-degree rotations.
        Bitmap transformed = new Bitmap(destW, destH, src.getBitDepth());
        transformed.copyPaletteMetadataFrom(src);
        transformed.setNativeAlpha(src.isNativeAlpha());
        Bitmap transformedMask = mask != null ? new Bitmap(destW, destH, mask.getBitDepth()) : null;
        if (transformedMask != null) {
            transformedMask.copyPaletteMetadataFrom(mask);
            transformedMask.setNativeAlpha(mask.isNativeAlpha());
        }
        byte[] srcPaletteIndices = src.getPaletteIndicesUnsafe();
        byte[] transformedIndices = srcPaletteIndices != null ? new byte[destW * destH] : null;
        boolean axisAligned =
                (px[0] == minX || px[0] == maxX) && (py[0] == minY || py[0] == maxY)
                        && (px[1] == minX || px[1] == maxX) && (py[1] == minY || py[1] == maxY)
                        && (px[2] == minX || px[2] == maxX) && (py[2] == minY || py[2] == maxY)
                        && (px[3] == minX || px[3] == maxX) && (py[3] == minY || py[3] == maxY);

        if (axisAligned) {
            double c0x = px[0] == minX ? 0.0 : 1.0;
            double c0y = py[0] == minY ? 0.0 : 1.0;
            double axisXX = (px[1] == minX ? 0.0 : 1.0) - c0x;
            double axisXY = (py[1] == minY ? 0.0 : 1.0) - c0y;
            double axisYX = (px[3] == minX ? 0.0 : 1.0) - c0x;
            double axisYY = (py[3] == minY ? 0.0 : 1.0) - c0y;

            for (int y = 0; y < destH; y++) {
                double dv = ((double) y + 0.5) / destH;
                for (int x = 0; x < destW; x++) {
                    double du = ((double) x + 0.5) / destW;
                    double relX = du - c0x;
                    double relY = dv - c0y;

                    double srcU = relX * axisXX + relY * axisXY;
                    double srcV = relX * axisYX + relY * axisYY;

                    int srcX = srcRect.left() + clamp((int) Math.floor(srcU * srcW), 0, srcW - 1);
                    int srcY = srcRect.top() + clamp((int) Math.floor(srcV * srcH), 0, srcH - 1);
                    if (srcX >= 0 && srcX < src.getWidth() && srcY >= 0 && srcY < src.getHeight()) {
                        transformed.setPixel(x, y, src.getPixel(srcX, srcY));
                        if (transformedIndices != null) {
                            transformedIndices[y * destW + x] = srcPaletteIndices[srcY * src.getWidth() + srcX];
                        }
                    }
                    int maskX = srcX - maskOffset.x();
                    int maskY = srcY - maskOffset.y();
                    if (transformedMask != null
                            && maskX >= 0 && maskX < mask.getWidth()
                            && maskY >= 0 && maskY < mask.getHeight()) {
                        transformedMask.setPixel(x, y, mask.getPixel(maskX, maskY));
                    }
                }
            }
        } else {
            // Fallback to the previous behaviour for quads that are not simple
            // axis-aligned rectangle transforms.
            boolean flipH = px[0] > px[1];
            boolean flipV = py[0] > py[3];
            for (int y = 0; y < destH; y++) {
                for (int x = 0; x < destW; x++) {
                    int srcX = flipH ? (srcW - 1 - (x * srcW / destW)) : (x * srcW / destW);
                    int srcY = flipV ? (srcH - 1 - (y * srcH / destH)) : (y * srcH / destH);
                    srcX += srcRect.left();
                    srcY += srcRect.top();
                    if (srcX >= 0 && srcX < src.getWidth() && srcY >= 0 && srcY < src.getHeight()) {
                        transformed.setPixel(x, y, src.getPixel(srcX, srcY));
                        if (transformedIndices != null) {
                            transformedIndices[y * destW + x] = srcPaletteIndices[srcY * src.getWidth() + srcX];
                        }
                    }
                    int maskX = srcX - maskOffset.x();
                    int maskY = srcY - maskOffset.y();
                    if (transformedMask != null
                            && maskX >= 0 && maskX < mask.getWidth()
                            && maskY >= 0 && maskY < mask.getHeight()) {
                        transformedMask.setPixel(x, y, mask.getPixel(maskX, maskY));
                    }
                }
            }
        }
        if (transformedIndices != null) {
            transformed.setPaletteIndices(transformedIndices);
        }

        Datum.PropList transformedProps = transformedQuadProps(props, transformedMask);
        java.util.List<Datum> transformedArgs = new java.util.ArrayList<>();
        transformedArgs.add(new Datum.ImageRef(transformed));
        transformedArgs.add(new Datum.Rect(minX, minY, maxX, maxY));
        transformedArgs.add(new Datum.Rect(0, 0, destW, destH));
        if (transformedProps != null && !transformedProps.isEmpty()) {
            transformedArgs.add(transformedProps);
        }

        return copyPixels(dest, transformedArgs);
    }

    private static Datum.PropList transformedQuadProps(Datum.PropList props, Bitmap transformedMask) {
        if (props == null) {
            if (transformedMask == null) {
                return null;
            }
            Datum.PropList copy = new Datum.PropList();
            copy.add("maskImage", new Datum.ImageRef(transformedMask), true);
            return copy;
        }
        Datum.PropList copy = new Datum.PropList(props.entries());
        copy.remove("maskImage");
        copy.remove("maskOffset");
        if (transformedMask != null) {
            copy.add("maskImage", new Datum.ImageRef(transformedMask), true);
        }
        return copy;
    }

    private static void preservePaletteIndicesOnCopy(Bitmap dest, Bitmap src,
                                                     int destX, int destY,
                                                     int srcX, int srcY,
                                                     int srcW, int srcH,
                                                     int destW, int destH,
                                                     Palette.InkMode ink, int blend,
                                                     Bitmap mask,
                                                     int colorRemap, int bgColorRemap,
                                                     Integer backgroundKeyRgb) {
        if (!canPreservePaletteIndices(dest, src, ink, blend, mask, colorRemap, bgColorRemap)) {
            return;
        }

        byte[] srcIndices = src.getPaletteIndicesUnsafe();
        if (srcIndices == null || srcIndices.length < src.getWidth() * src.getHeight()) {
            return;
        }

        if (dest.getImagePalette() == null && src.getImagePalette() != null) {
            dest.setImagePalette(src.getImagePalette());
            if (src.getPaletteRefCastLib() >= 1 && src.getPaletteRefMemberNum() >= 1) {
                dest.setPaletteRefCastMember(src.getPaletteRefCastLib(), src.getPaletteRefMemberNum());
            } else if (src.getPaletteRefSystemName() != null && !src.getPaletteRefSystemName().isEmpty()) {
                dest.setPaletteRefSystemName(src.getPaletteRefSystemName());
            }
        }

        byte[] destIndices = dest.ensurePaletteIndices();

        for (int y = 0; y < destH; y++) {
            int sy = srcY + (y * srcH / destH);
            int dy = destY + y;
            if (sy < 0 || sy >= src.getHeight() || dy < 0 || dy >= dest.getHeight()) {
                continue;
            }
            for (int x = 0; x < destW; x++) {
                int sx = srcX + (x * srcW / destW);
                int dx = destX + x;
                if (sx < 0 || sx >= src.getWidth() || dx < 0 || dx >= dest.getWidth()) {
                    continue;
                }
                int srcPixel = src.getPixel(sx, sy);
                if (shouldSkipPaletteIndexPreserve(srcPixel, ink, backgroundKeyRgb)) {
                    continue;
                }
                destIndices[dy * dest.getWidth() + dx] = srcIndices[sy * src.getWidth() + sx];
                Palette palette = dest.getImagePalette();
                if (palette != null) {
                    int alpha = (dest.getPixel(dx, dy) >>> 24) & 0xFF;
                    if (alpha != 0) {
                        int rgb = palette.getColor(srcIndices[sy * src.getWidth() + sx] & 0xFF) & 0xFFFFFF;
                        dest.setPixelPreservePaletteIndex(dx, dy, (alpha << 24) | rgb);
                    }
                }
            }
        }

    }

    private static boolean shouldSkipPaletteIndexPreserve(int srcPixel, Palette.InkMode ink,
                                                          Integer backgroundKeyRgb) {
        int alpha = (srcPixel >>> 24) & 0xFF;
        if (alpha == 0) {
            return true;
        }
        if (ink == Palette.InkMode.BACKGROUND_TRANSPARENT) {
            int keyRgb = backgroundKeyRgb != null ? backgroundKeyRgb : 0xFFFFFF;
            return (srcPixel & 0xFFFFFF) == keyRgb;
        }
        return false;
    }

    private static boolean canPreservePaletteIndices(Bitmap dest, Bitmap src,
                                                     Palette.InkMode ink, int blend,
                                                     Bitmap mask,
                                                     int colorRemap, int bgColorRemap) {
        return dest != null
                && src != null
                && canAcceptCopiedPaletteIndices(dest)
                && src.getBitDepth() <= 8
                && dest.getBitDepth() <= 8
                && dest.getBitDepth() >= src.getBitDepth()
                && src.getPaletteIndicesUnsafe() != null
                && palettesAreCompatibleForIndexPreserve(dest, src)
                && (ink == Palette.InkMode.COPY
                    || ink == Palette.InkMode.MATTE
                    || ink == Palette.InkMode.BACKGROUND_TRANSPARENT)
                && blend >= 255
                && mask == null
                && colorRemap < 0
                && bgColorRemap < 0;
    }

    private static boolean palettesAreCompatibleForIndexPreserve(Bitmap dest, Bitmap src) {
        Palette destPalette = dest.getImagePalette();
        Palette srcPalette = src.getImagePalette();
        return destPalette == null || srcPalette == null || destPalette == srcPalette;
    }

    private static boolean canAcceptCopiedPaletteIndices(Bitmap dest) {
        return dest != null && (!dest.isScriptModified() || dest.getPaletteIndicesUnsafe() != null);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int resolveBackgroundTransparentKey(Bitmap src, Datum.Rect srcRect, int explicitBgColorRemap,
                                                       boolean sourceWasColorized) {
        if (explicitBgColorRemap >= 0
                && (sourceWasColorized
                || (src != null && src.getBitDepth() <= 8)
                || explicitBgColorRemap == 0xFFFFFF
                || rgbLooksLikeRegionBackground(src, srcRect, explicitBgColorRemap))) {
            return explicitBgColorRemap & 0xFFFFFF;
        }
        int textBackgroundRgb = src != null ? src.getOpaqueTextRenderBackgroundRgb() : -1;
        if (textBackgroundRgb >= 0 && rgbLooksLikeRegionBackground(src, srcRect, textBackgroundRgb)) {
            return textBackgroundRgb;
        }
        Integer inferredTextBackground = inferTextLikeBackgroundKey(src, srcRect);
        if (inferredTextBackground != null) {
            return inferredTextBackground;
        }
        // Director's regular copyPixels default remains white for non-text artwork.
        return 0xFFFFFF;
    }

    private static boolean rgbLooksLikeRegionBackground(Bitmap src, Datum.Rect srcRect, int rgb) {
        if (src == null || srcRect == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return false;
        }

        int left = clamp(Math.min(srcRect.left(), srcRect.right()), 0, src.getWidth());
        int top = clamp(Math.min(srcRect.top(), srcRect.bottom()), 0, src.getHeight());
        int right = clamp(Math.max(srcRect.left(), srcRect.right()), 0, src.getWidth());
        int bottom = clamp(Math.max(srcRect.top(), srcRect.bottom()), 0, src.getHeight());
        if (left >= right || top >= bottom) {
            return false;
        }

        int keyRgb = rgb & 0xFFFFFF;
        if (!opaqueCornersMatch(src, left, top, right, bottom, keyRgb)) {
            return false;
        }

        int opaquePixels = 0;
        int matchingPixels = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = src.getPixel(x, y);
                if (((pixel >>> 24) & 0xFF) == 0) {
                    continue;
                }
                opaquePixels++;
                if ((pixel & 0xFFFFFF) == keyRgb) {
                    matchingPixels++;
                }
            }
        }
        if (opaquePixels == 0 || matchingPixels * 100 < opaquePixels * 35) {
            return false;
        }

        int edgeRgb = dominantOpaqueEdgeRgb(src, left, top, right, bottom);
        return edgeRgb == keyRgb;
    }

    private static Integer inferTextLikeBackgroundKey(Bitmap src, Datum.Rect srcRect) {
        if (src == null || srcRect == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return null;
        }

        int left = clamp(Math.min(srcRect.left(), srcRect.right()), 0, src.getWidth());
        int top = clamp(Math.min(srcRect.top(), srcRect.bottom()), 0, src.getHeight());
        int right = clamp(Math.max(srcRect.left(), srcRect.right()), 0, src.getWidth());
        int bottom = clamp(Math.max(srcRect.top(), srcRect.bottom()), 0, src.getHeight());
        if (left >= right || top >= bottom) {
            return null;
        }

        int edgeRgb = dominantOpaqueEdgeRgb(src, left, top, right, bottom);
        if (edgeRgb < 0 || edgeRgb == 0xFFFFFF || !isLowSaturationRgb(edgeRgb)) {
            return null;
        }
        if (!opaqueCornersMatch(src, left, top, right, bottom, edgeRgb)) {
            return null;
        }

        int opaquePixels = 0;
        int backgroundPixels = 0;
        int foregroundPixels = 0;
        int distinctForegroundColors = 0;
        int[] foregroundColors = new int[33];
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = src.getPixel(x, y);
                if (((pixel >>> 24) & 0xFF) == 0) {
                    continue;
                }
                opaquePixels++;
                int rgb = pixel & 0xFFFFFF;
                if (rgb == edgeRgb) {
                    backgroundPixels++;
                    continue;
                }

                foregroundPixels++;
                boolean known = false;
                for (int i = 0; i < distinctForegroundColors; i++) {
                    if (foregroundColors[i] == rgb) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    if (distinctForegroundColors >= foregroundColors.length) {
                        return null;
                    }
                    foregroundColors[distinctForegroundColors++] = rgb;
                }
            }
        }

        if (opaquePixels == 0 || foregroundPixels == 0 || backgroundPixels == 0) {
            return null;
        }
        if (foregroundPixels * 100 > opaquePixels * 60) {
            return null;
        }
        return edgeRgb;
    }

    private static int dominantOpaqueEdgeRgb(Bitmap src, int left, int top, int right, int bottom) {
        int[] colors = new int[64];
        int[] counts = new int[64];
        int distinct = 0;
        int opaqueEdgePixels = 0;

        for (int x = left; x < right; x++) {
            int topPixel = src.getPixel(x, top);
            if (((topPixel >>> 24) & 0xFF) != 0) {
                distinct = countEdgeRgb(topPixel & 0xFFFFFF, colors, counts, distinct);
                opaqueEdgePixels++;
            }
            int bottomPixel = src.getPixel(x, bottom - 1);
            if (bottom - 1 != top && ((bottomPixel >>> 24) & 0xFF) != 0) {
                distinct = countEdgeRgb(bottomPixel & 0xFFFFFF, colors, counts, distinct);
                opaqueEdgePixels++;
            }
        }
        for (int y = top + 1; y < bottom - 1; y++) {
            int leftPixel = src.getPixel(left, y);
            if (((leftPixel >>> 24) & 0xFF) != 0) {
                distinct = countEdgeRgb(leftPixel & 0xFFFFFF, colors, counts, distinct);
                opaqueEdgePixels++;
            }
            int rightPixel = src.getPixel(right - 1, y);
            if (right - 1 != left && ((rightPixel >>> 24) & 0xFF) != 0) {
                distinct = countEdgeRgb(rightPixel & 0xFFFFFF, colors, counts, distinct);
                opaqueEdgePixels++;
            }
        }

        int bestRgb = -1;
        int bestCount = 0;
        for (int i = 0; i < distinct; i++) {
            if (counts[i] > bestCount) {
                bestRgb = colors[i];
                bestCount = counts[i];
            }
        }
        if (opaqueEdgePixels == 0 || bestRgb < 0 || bestCount * 4 < opaqueEdgePixels * 3) {
            return -1;
        }
        return bestRgb;
    }

    private static int countEdgeRgb(int rgb, int[] colors, int[] counts, int distinct) {
        for (int i = 0; i < distinct; i++) {
            if (colors[i] == rgb) {
                counts[i]++;
                return distinct;
            }
        }
        if (distinct >= colors.length) {
            return distinct;
        }
        colors[distinct] = rgb;
        counts[distinct] = 1;
        return distinct + 1;
    }

    private static boolean opaqueCornersMatch(Bitmap src, int left, int top, int right, int bottom, int rgb) {
        int[] xs = {left, right - 1, left, right - 1};
        int[] ys = {top, top, bottom - 1, bottom - 1};
        int opaqueCorners = 0;
        for (int i = 0; i < xs.length; i++) {
            int pixel = src.getPixel(xs[i], ys[i]);
            if (((pixel >>> 24) & 0xFF) == 0) {
                continue;
            }
            opaqueCorners++;
            if ((pixel & 0xFFFFFF) != rgb) {
                return false;
            }
        }
        return opaqueCorners > 0;
    }

    private static boolean isLowSaturationRgb(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min <= 4;
    }

    private static int percentToBlendAlpha(Datum blendDatum) {
        int alpha = (int) Math.round(blendDatum.toDouble() * 255.0 / 100.0);
        return Math.max(0, Math.min(255, alpha));
    }

    private static int byteToBlendAlpha(Datum blendDatum) {
        return Math.max(0, Math.min(255, blendDatum.toInt()));
    }

    private static boolean hasOpaqueBackgroundKeyBorder(Bitmap src, int srcX, int srcY,
                                                        int width, int height,
                                                        Integer backgroundKeyRgb) {
        if (src == null || backgroundKeyRgb == null || width <= 0 || height <= 0) {
            return false;
        }
        int keyRgb = backgroundKeyRgb & 0xFFFFFF;
        int maxX = src.getWidth() - 1;
        int maxY = src.getHeight() - 1;
        int left = clamp(srcX, 0, maxX);
        int top = clamp(srcY, 0, maxY);
        int right = clamp(srcX + width - 1, 0, maxX);
        int bottom = clamp(srcY + height - 1, 0, maxY);

        for (int x = left; x <= right; x++) {
            if (isOpaqueKeyPixel(src.getPixel(x, top), keyRgb)
                    || isOpaqueKeyPixel(src.getPixel(x, bottom), keyRgb)) {
                return true;
            }
        }
        for (int y = top + 1; y < bottom; y++) {
            if (isOpaqueKeyPixel(src.getPixel(left, y), keyRgb)
                    || isOpaqueKeyPixel(src.getPixel(right, y), keyRgb)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpaqueKeyPixel(int pixel, int keyRgb) {
        return ((pixel >>> 24) & 0xFF) == 255 && (pixel & 0xFFFFFF) == keyRgb;
    }

    private static boolean isMostlyGrayscale(Bitmap src, Datum.Rect srcRect) {
        if (src == null || srcRect == null) {
            return false;
        }

        int srcW = srcRect.right() - srcRect.left();
        int srcH = srcRect.bottom() - srcRect.top();
        if (srcW <= 0 || srcH <= 0) {
            return false;
        }

        int pixelCount = srcW * srcH;
        int sampleStep = pixelCount <= 65536 ? 1 : Math.max(1, pixelCount / 2048);
        for (int i = 0; i < pixelCount; i += sampleStep) {
            int sx = srcRect.left() + (i % srcW);
            int sy = srcRect.top() + (i / srcW);
            int p = src.getPixel(sx, sy);
            if ((p >>> 24) == 0) {
                continue;
            }
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            if (Math.abs(r - g) > 2 || Math.abs(g - b) > 2) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInverseWhiteAlphaMask(Bitmap src, int srcX, int srcY, int width, int height) {
        boolean hasOpaqueWhite = false;
        boolean hasTransparentInk = false;
        int[][] corners = uniqueCorners(width, height);
        int opaqueWhiteCorners = 0;
        int checkedCorners = 0;
        for (int[] corner : corners) {
            int px = srcX + corner[0];
            int py = srcY + corner[1];
            if (px < 0 || px >= src.getWidth() || py < 0 || py >= src.getHeight()) {
                continue;
            }
            checkedCorners++;
            int pixel = src.getPixel(px, py);
            if (((pixel >>> 24) & 0xFF) != 0 && (pixel & 0xFFFFFF) == 0xFFFFFF) {
                opaqueWhiteCorners++;
            }
        }
        if (checkedCorners == 0 || opaqueWhiteCorners < checkedCorners) {
            return false;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = srcX + x;
                int py = srcY + y;
                if (px < 0 || px >= src.getWidth() || py < 0 || py >= src.getHeight()) {
                    continue;
                }
                int pixel = src.getPixel(px, py);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha == 0) {
                    hasTransparentInk = true;
                    continue;
                }
                if ((pixel & 0xFFFFFF) != 0xFFFFFF) {
                    return false;
                }
                hasOpaqueWhite = true;
            }
        }
        return hasOpaqueWhite && hasTransparentInk;
    }

    private static Bitmap invertWhiteAlphaMaskToInk(Bitmap src, int srcX, int srcY, int width, int height,
                                                    int inkRgb) {
        Bitmap inverted = new Bitmap(width, height, 32);
        inverted.setNativeAlpha(true);
        int ink = 0xFF000000 | (inkRgb & 0xFFFFFF);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = srcX + x;
                int py = srcY + y;
                if (px < 0 || px >= src.getWidth() || py < 0 || py >= src.getHeight()) {
                    continue;
                }
                int pixel = src.getPixel(px, py);
                if (((pixel >>> 24) & 0xFF) == 0) {
                    inverted.setPixel(x, y, ink);
                }
            }
        }
        return inverted;
    }

    private static int resolveInverseMaskInkRgb(int explicitBgColorRemap) {
        if (explicitBgColorRemap >= 0) {
            return 0x000000;
        }
        return DEFAULT_INVERSE_TEXT_MASK_RGB;
    }

    private static Bitmap multiplyBitmapColorForDarken(Bitmap src, int tintRgb, boolean indexedShade) {
        if (src.getPaletteIndicesUnsafe() != null && src.getBitDepth() <= 8) {
            return multiplyIndexedBitmapColorForDarken(src, tintRgb, indexedShade);
        }
        return multiplyBitmapColor(src, tintRgb);
    }

    private static Bitmap multiplyBitmapColor(Bitmap src, int tintRgb) {
        if (tintRgb == 0xFFFFFF) {
            return src;
        }

        int tintR = (tintRgb >> 16) & 0xFF;
        int tintG = (tintRgb >> 8) & 0xFF;
        int tintB = tintRgb & 0xFF;

        Bitmap tinted = new Bitmap(src.getWidth(), src.getHeight(), src.getBitDepth());
        tinted.copyPaletteMetadataFrom(src);

        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int pixel = src.getPixel(x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                int r = ((pixel >> 16) & 0xFF) * tintR / 255;
                int g = ((pixel >> 8) & 0xFF) * tintG / 255;
                int b = (pixel & 0xFF) * tintB / 255;
                tinted.setPixel(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return tinted;
    }

    private static Bitmap multiplyIndexedBitmapColorForDarken(Bitmap src, int tintRgb, boolean indexedShade) {
        if (tintRgb == 0xFFFFFF) {
            return src;
        }

        int tintR = (tintRgb >> 16) & 0xFF;
        int tintG = (tintRgb >> 8) & 0xFF;
        int tintB = tintRgb & 0xFF;

        Bitmap tinted = new Bitmap(src.getWidth(), src.getHeight(), src.getBitDepth());
        tinted.copyPaletteMetadataFrom(src);
        byte[] darkenShadeIndices = indexedShade ? src.getPaletteIndicesUnsafe() : null;

        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int pixel = src.getPixel(x, y);
                int alpha = (pixel >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }

                int srcR = (pixel >> 16) & 0xFF;
                int srcG = (pixel >> 8) & 0xFF;
                int srcB = pixel & 0xFF;
                int r;
                int g;
                int b;
                boolean customPaletteColorShade = !indexedShade && src.getImagePalette() != null;
                if (!customPaletteColorShade) {
                    int shade = shadeForDarken(src, x, y, srcR, srcG, srcB, indexedShade, darkenShadeIndices);
                    r = multiplyDarkenChannel(shade, tintR, indexedShade);
                    g = multiplyDarkenChannel(shade, tintG, indexedShade);
                    b = multiplyDarkenChannel(shade, tintB, indexedShade);
                } else {
                    r = multiplyDarkenChannel(srcR, tintR, true);
                    g = multiplyDarkenChannel(srcG, tintG, true);
                    b = multiplyDarkenChannel(srcB, tintB, true);
                }
                tinted.setPixel(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return tinted;
    }

    private static int multiplyDarkenChannel(int source, int tint, boolean preserveFullTint) {
        return preserveFullTint && tint == 0xFF ? source : source * tint / 256;
    }

    private static boolean usesIndexedShadeForDarken(Bitmap src, Bitmap mask) {
        if (src == null || src.getPaletteIndicesUnsafe() == null || src.getBitDepth() > 8) {
            return false;
        }
        if (isIndexShadePalette(src)) {
            return true;
        }
        // Custom-palette indexed images, such as carpet_polar_small, carry real
        // color shades. Their palette index order is not a grayscale ramp.
        return false;
    }

    private static boolean isIndexShadePalette(Bitmap src) {
        if (src.getImagePalette() == null) {
            return false;
        }
        String name = src.getImagePalette().getName();
        return "Grayscale".equalsIgnoreCase(name)
                || "System - Mac".equalsIgnoreCase(name)
                || "System Mac".equalsIgnoreCase(name);
    }

    private static int shadeForDarken(Bitmap src, int x, int y, int r, int g, int b,
                                      boolean indexedShade, byte[] indices) {
        if (indexedShade
                && indices != null
                && x >= 0 && x < src.getWidth()
                && y >= 0 && y < src.getHeight()) {
            int offset = y * src.getWidth() + x;
            if (offset >= 0 && offset < indices.length) {
                return 255 - (indices[offset] & 0xFF);
            }
        }
        return r;
    }

    /**
     * image.crop(rect) - Crop image to rectangle, return new image.
     */
    private static Datum crop(Bitmap bmp, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        if (!(args.get(0) instanceof Datum.Rect rect)) return Datum.VOID;

        int w = rect.right() - rect.left();
        int h = rect.bottom() - rect.top();
        if (w <= 0 || h <= 0) return Datum.VOID;

        Bitmap cropped = bmp.getRegion(rect.left(), rect.top(), w, h);
        cropped.clearAnchorPoint();
        return new Datum.ImageRef(cropped);
    }

    /**
     * Look up a property by name in a PropList, trying the given key first,
     * then common casing variants (lowercase, capitalized). Returns Datum.VOID if not found.
     */
    private static Datum getPropIgnoreCase(Datum.PropList pl, String... keys) {
        for (String key : keys) {
            Datum val = pl.get(key);
            if (val != null) return val;
        }
        return Datum.VOID;
    }

    private static MaskOffset maskOffsetFromPropList(Datum.PropList pl) {
        Datum maskOffsetDatum = getPropIgnoreCase(pl, "maskOffset", "maskoffset", "MaskOffset");
        if (maskOffsetDatum instanceof Datum.Point p) {
            return new MaskOffset(p.x(), p.y());
        }
        if (maskOffsetDatum instanceof Datum.List list && list.items().size() >= 2) {
            return new MaskOffset(list.items().get(0).toInt(), list.items().get(1).toInt());
        }
        return new MaskOffset(0, 0);
    }

    /**
     * Convert Director ink number to InkMode enum.
     */
    private static Palette.InkMode inkFromInt(int inkNum) {
        return switch (inkNum) {
            case 0 -> Palette.InkMode.COPY;
            case 1 -> Palette.InkMode.TRANSPARENT;
            case 2 -> Palette.InkMode.REVERSE;
            case 3 -> Palette.InkMode.GHOST;
            case 4 -> Palette.InkMode.NOT_COPY;
            case 5 -> Palette.InkMode.NOT_TRANSPARENT;
            case 6 -> Palette.InkMode.NOT_REVERSE;
            case 7 -> Palette.InkMode.NOT_GHOST;
            case 8 -> Palette.InkMode.MATTE;
            case 9 -> Palette.InkMode.MASK;
            case 32 -> Palette.InkMode.BLEND;
            case 33 -> Palette.InkMode.ADD_PIN;
            case 34 -> Palette.InkMode.ADD;
            case 35 -> Palette.InkMode.SUBTRACT_PIN;
            case 36 -> Palette.InkMode.BACKGROUND_TRANSPARENT;
            case 37 -> Palette.InkMode.LIGHTEST;
            case 38 -> Palette.InkMode.SUBTRACT;
            case 39 -> Palette.InkMode.DARKEST;
            case 40 -> Palette.InkMode.LIGHTEN;
            case 41 -> Palette.InkMode.DARKEN;
            default -> Palette.InkMode.COPY;
        };
    }

    private static Palette.InkMode inkFromDatum(Datum inkDatum) {
        if (inkDatum instanceof Datum.Int inkInt) {
            return inkFromInt(inkInt.value());
        }
        if (inkDatum instanceof Datum.Symbol sym) {
            return inkFromName(sym.name());
        }
        if (inkDatum instanceof Datum.Str str) {
            return inkFromName(str.value());
        }
        return null;
    }

    private static Palette.InkMode inkFromName(String inkName) {
        InkMode inkMode = InkMode.fromNameOrNull(inkName);
        return inkMode != null ? inkFromInt(inkMode.code()) : null;
    }
}
