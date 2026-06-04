package com.libreshockwave.player.render.output;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;
import com.libreshockwave.player.render.pipeline.RenderSprite;

import java.util.Arrays;

/**
 * Pure-Java software renderer that composites a FrameSnapshot into an ARGB int[] buffer.
 * No AWT dependency — works in WASM via TeaVM and anywhere else.
 */
public final class SoftwareFrameRenderer {

    private SoftwareFrameRenderer() {}

    /**
     * Render a FrameSnapshot to a Bitmap using pure int[] compositing.
     */
    public static Bitmap renderFrame(FrameSnapshot snapshot, int stageWidth, int stageHeight) {
        if (stageWidth <= 0 || stageHeight <= 0
                || (long) stageWidth * (long) stageHeight > Integer.MAX_VALUE) {
            return new Bitmap(1, 1, 32, new int[]{0xFF000000});
        }
        int pixelCount = stageWidth * stageHeight;
        int[] argb = new int[pixelCount];
        renderFrameInto(snapshot, stageWidth, stageHeight, argb);
        return new Bitmap(stageWidth, stageHeight, 32, argb);
    }

    /**
     * Render a FrameSnapshot into a caller-owned ARGB buffer.
     * This lets the WASM renderer reuse its stage buffer instead of allocating
     * a full-size temporary bitmap every frame.
     */
    public static void renderFrameInto(FrameSnapshot snapshot, int stageWidth, int stageHeight, int[] argb) {
        if (snapshot == null || argb == null || stageWidth <= 0 || stageHeight <= 0
                || (long) stageWidth * (long) stageHeight > Integer.MAX_VALUE) {
            return;
        }
        int pixelCount = stageWidth * stageHeight;
        if (argb.length < pixelCount) {
            return;
        }

        // Match AwtFrameRenderer behavior: stageImage replaces background, not composited on top
        Bitmap stageImage = snapshot.stageImage();
        if (stageImage != null) {
            Arrays.fill(argb, 0, pixelCount, 0);
            // Copy stageImage pixels directly (same as AWT drawImage with no bg fill)
            int[] srcPixels = stageImage.getPixels();
            int srcW = stageImage.getWidth();
            int srcH = stageImage.getHeight();
            if (srcPixels != null) {
                for (int y = 0; y < Math.min(srcH, stageHeight); y++) {
                    for (int x = 0; x < Math.min(srcW, stageWidth); x++) {
                        int srcIdx = y * srcW + x;
                        if (srcIdx >= 0 && srcIdx < srcPixels.length) {
                            argb[y * stageWidth + x] = srcPixels[srcIdx];
                        }
                    }
                }
            }
        } else {
            // Fill with background color (opaque)
            int bg = snapshot.backgroundColor() | 0xFF000000;
            for (int i = 0; i < pixelCount; i++) {
                argb[i] = bg;
            }
        }

        // 3. Composite each visible sprite in order
        for (RenderSprite sprite : snapshot.sprites()) {
            try {
                compositeSprite(argb, stageWidth, stageHeight, sprite);
            } catch (Throwable e) {
                System.err.println("[SoftwareFrameRenderer] skipped sprite channel="
                        + sprite.getChannel()
                        + " member=" + sprite.getMemberName()
                        + " size=" + sprite.getWidth() + "x" + sprite.getHeight()
                        + " error=" + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
        }

        // Stage border disabled: Director Shockwave player in browser does not
        // draw a border around the stage — only the standalone projector does.
        // drawStageBorder(argb, stageWidth, stageHeight, 0xFF000000);
    }

    private static void compositeSprite(int[] argb, int stageWidth, int stageHeight, RenderSprite sprite) {
        if (!sprite.isVisible()) return;

        Bitmap baked = sprite.getBakedBitmap();
        if (baked == null) return;
        if (baked.getWidth() <= 0 || baked.getHeight() <= 0) return;
        if (baked.getPixels() == null || baked.getPixels().length == 0) return;

        int sx = sprite.getX();
        int sy = sprite.getY();
        int sw = sprite.getWidth() > 0 ? sprite.getWidth() : baked.getWidth();
        int sh = sprite.getHeight() > 0 ? sprite.getHeight() : baked.getHeight();
        int blend = sprite.getBlend();
        InkMode ink = sprite.getInkMode();
        boolean flipH = sprite.isFlipH() ^ sprite.hasDirectorHorizontalMirror();
        boolean flipV = sprite.isFlipV();

        if (hasAffineTransform(sprite)) {
            blitBitmapTransformed(argb, stageWidth, stageHeight,
                    baked.getPixels(), baked.getWidth(), baked.getHeight(),
                    sx, sy, sw, sh, blend, ink, flipH, flipV,
                    sprite.getRotation(), sprite.getSkew(),
                    sprite.getRegistrationX(), sprite.getRegistrationY());
            return;
        }

        if (sw == baked.getWidth() && sh == baked.getHeight()) {
            blitBitmap(argb, stageWidth, stageHeight,
                    baked.getPixels(), baked.getWidth(), baked.getHeight(),
                    sx, sy, blend, ink, flipH, flipV);
        } else {
            blitBitmapScaled(argb, stageWidth, stageHeight,
                    baked.getPixels(), baked.getWidth(), baked.getHeight(),
                    sx, sy, sw, sh, blend, ink, flipH, flipV);
        }
    }

    private static boolean hasAffineTransform(RenderSprite sprite) {
        if (sprite.hasDirectorHorizontalMirror()) {
            return false;
        }
        return normalizeAngle(sprite.getRotation()) != 0 || normalizeAngle(sprite.getSkew()) != 0;
    }

    private static int normalizeAngle(double angle) {
        int normalized = (int) Math.round(angle) % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        return normalized;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawStageBorder(int[] argb, int w, int h, int color) {
        for (int x = 0; x < w; x++) {
            argb[x] = color;
            argb[(h - 1) * w + x] = color;
        }
        for (int y = 0; y < h; y++) {
            argb[y * w] = color;
            argb[y * w + w - 1] = color;
        }
    }

    static void blitBitmapTransformed(int[] argb, int stageWidth, int stageHeight,
                                      int[] srcPixels, int srcW, int srcH,
                                      int dstX, int dstY, int dstW, int dstH,
                                      int blend, InkMode ink, boolean flipH, boolean flipV,
                                      double rotation, double skew,
                                      int registrationX, int registrationY) {
        if (srcPixels == null || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;
        if ((long) srcPixels.length < (long) srcW * srcH) return;

        double rotationRad = Math.toRadians(rotation);
        double cos = Math.cos(rotationRad);
        double sin = Math.sin(rotationRad);
        double shear = Math.tan(Math.toRadians(skew));

        registrationX = clamp(registrationX, 0, dstW);
        registrationY = clamp(registrationY, 0, dstH);

        double m00 = cos;
        double m01 = shear * cos - sin;
        double m10 = sin;
        double m11 = shear * sin + cos;

        double anchorX = dstX + registrationX;
        double anchorY = dstY + registrationY;
        double x0 = transformX(anchorX, m00, m01, -registrationX, -registrationY);
        double y0 = transformY(anchorY, m10, m11, -registrationX, -registrationY);
        double x1 = transformX(anchorX, m00, m01, dstW - registrationX, -registrationY);
        double y1 = transformY(anchorY, m10, m11, dstW - registrationX, -registrationY);
        double x2 = transformX(anchorX, m00, m01, dstW - registrationX, dstH - registrationY);
        double y2 = transformY(anchorY, m10, m11, dstW - registrationX, dstH - registrationY);
        double x3 = transformX(anchorX, m00, m01, -registrationX, dstH - registrationY);
        double y3 = transformY(anchorY, m10, m11, -registrationX, dstH - registrationY);

        int minX = Math.max(0, (int) Math.floor(Math.min(Math.min(x0, x1), Math.min(x2, x3))));
        int minY = Math.max(0, (int) Math.floor(Math.min(Math.min(y0, y1), Math.min(y2, y3))));
        int maxX = Math.min(stageWidth, (int) Math.ceil(Math.max(Math.max(x0, x1), Math.max(x2, x3))));
        int maxY = Math.min(stageHeight, (int) Math.ceil(Math.max(Math.max(y0, y1), Math.max(y2, y3))));
        if (minX >= maxX || minY >= maxY) return;

        double det = m00 * m11 - m10 * m01;
        if (Math.abs(det) < 0.000001) return;
        double inv00 = m11 / det;
        double inv01 = -m01 / det;
        double inv10 = -m10 / det;
        double inv11 = m00 / det;

        int srcLen = srcPixels.length;
        int argbLen = argb.length;
        boolean useSpecialInk = isSpecialCompositingInk(ink);

        for (int dy = minY; dy < maxY; dy++) {
            for (int dx = minX; dx < maxX; dx++) {
                double relX = dx + 0.5 - anchorX;
                double relY = dy + 0.5 - anchorY;
                double localX = inv00 * relX + inv01 * relY + registrationX;
                double localY = inv10 * relX + inv11 * relY + registrationY;
                if (localX < 0.0 || localX >= dstW || localY < 0.0 || localY >= dstH) {
                    continue;
                }

                int srcX = (int) Math.floor(localX * srcW / dstW);
                int srcY = (int) Math.floor(localY * srcH / dstH);
                if (flipH) srcX = srcW - 1 - srcX;
                if (flipV) srcY = srcH - 1 - srcY;
                if (srcX < 0 || srcX >= srcW || srcY < 0 || srcY >= srcH) continue;

                int srcIdx = srcY * srcW + srcX;
                if (srcIdx < 0 || srcIdx >= srcLen) continue;

                int dstIdx = dy * stageWidth + dx;
                if (dstIdx < 0 || dstIdx >= argbLen) continue;

                compositePixel(argb, dstIdx, srcPixels[srcIdx], blend, ink, useSpecialInk);
            }
        }
    }

    private static double transformX(double anchorX, double m00, double m01,
                                     double localX, double localY) {
        return anchorX + m00 * localX + m01 * localY;
    }

    private static double transformY(double anchorY, double m10, double m11,
                                     double localX, double localY) {
        return anchorY + m10 * localX + m11 * localY;
    }

    // ========================================================================
    // Alpha blitting (unscaled)
    // ========================================================================

    static void blitBitmap(int[] argb, int stageWidth, int stageHeight,
                           int[] srcPixels, int srcW, int srcH,
                           int dstX, int dstY, int blend, InkMode ink,
                           boolean flipH, boolean flipV) {
        if (srcPixels == null || srcW <= 0 || srcH <= 0) return;
        if ((long) srcPixels.length < (long) srcW * srcH) return;

        int sx0 = Math.max(0, -dstX);
        int sy0 = Math.max(0, -dstY);
        int sx1 = Math.min(srcW, stageWidth - dstX);
        int sy1 = Math.min(srcH, stageHeight - dstY);
        if (sx0 >= sx1 || sy0 >= sy1) return;

        int argbLen = argb.length;
        boolean useSpecialInk = isSpecialCompositingInk(ink);

        for (int sy = sy0; sy < sy1; sy++) {
            int fetchY = flipV ? (srcH - 1 - sy) : sy;
            for (int sx = sx0; sx < sx1; sx++) {
                int fetchX = flipH ? (srcW - 1 - sx) : sx;
                int srcIdx = fetchY * srcW + fetchX;
                int src = srcPixels[srcIdx];
                int dstIdx = (dstY + sy) * stageWidth + (dstX + sx);
                if (dstIdx < 0 || dstIdx >= argbLen) continue;

                compositePixel(argb, dstIdx, src, blend, ink, useSpecialInk);
            }
        }
    }

    // ========================================================================
    // Alpha blitting (scaled — nearest neighbor)
    // ========================================================================

    static void blitBitmapScaled(int[] argb, int stageWidth, int stageHeight,
                                 int[] srcPixels, int srcW, int srcH,
                                 int dstX, int dstY, int dstW, int dstH, int blend, InkMode ink,
                                 boolean flipH, boolean flipV) {
        if (srcPixels == null || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;
        if ((long) srcPixels.length < (long) srcW * srcH) return;

        int dx0 = Math.max(0, dstX);
        int dy0 = Math.max(0, dstY);
        int dx1 = Math.min(stageWidth, dstX + dstW);
        int dy1 = Math.min(stageHeight, dstY + dstH);
        if (dx0 >= dx1 || dy0 >= dy1) return;

        int srcLen = srcPixels.length;
        int argbLen = argb.length;
        boolean useSpecialInk = isSpecialCompositingInk(ink);

        for (int dy = dy0; dy < dy1; dy++) {
            int srcY = ((dy - dstY) * srcH) / dstH;
            if (flipV) srcY = srcH - 1 - srcY;
            if (srcY < 0 || srcY >= srcH) continue;

            for (int dx = dx0; dx < dx1; dx++) {
                int srcX = ((dx - dstX) * srcW) / dstW;
                if (flipH) srcX = srcW - 1 - srcX;
                if (srcX < 0 || srcX >= srcW) continue;

                int srcIdx = srcY * srcW + srcX;
                if (srcIdx < 0 || srcIdx >= srcLen) continue;

                int src = srcPixels[srcIdx];
                int dstIdx = dy * stageWidth + dx;
                if (dstIdx < 0 || dstIdx >= argbLen) continue;

                compositePixel(argb, dstIdx, src, blend, ink, useSpecialInk);
            }
        }
    }

    // ========================================================================
    // Alpha composite helper
    // ========================================================================

    /**
     * Returns true for ink modes that need special compositing (not standard alpha blend).
     */
    private static boolean isSpecialCompositingInk(InkMode ink) {
        return ink == InkMode.ADD_PIN || ink == InkMode.ADD
            || ink == InkMode.SUBTRACT_PIN || ink == InkMode.SUBTRACT
            || ink == InkMode.LIGHTEST || ink == InkMode.DARKEST
            || ink == InkMode.LIGHTEN
            || ink == InkMode.REVERSE || ink == InkMode.GHOST
            || ink == InkMode.NOT_COPY || ink == InkMode.NOT_TRANSPARENT
            || ink == InkMode.NOT_REVERSE || ink == InkMode.NOT_GHOST;
    }

    private static void compositePixel(int[] argb, int dstIdx, int src, int blend,
                                       InkMode ink, boolean useSpecialInk) {
        int srcA = (src >> 24) & 0xFF;
        if (srcA == 0) return;

        if (useSpecialInk) {
            if (blend < 100) {
                srcA = (srcA * blend) / 100;
                if (srcA == 0) return;
            }
            compositeSpecialInk(argb, dstIdx, src, srcA, ink);
        } else if (blend < 100) {
            alphaCompositePercent(argb, dstIdx, src, srcA, blend);
        } else if (srcA >= 255) {
            argb[dstIdx] = src | 0xFF000000;
        } else {
            alphaComposite(argb, dstIdx, src, srcA);
        }
    }

    /**
     * Composite a source pixel onto the destination using a special ink mode.
     * The srcA parameter controls how much of the effect is applied (blend).
     */
    private static void compositeSpecialInk(int[] argb, int dstIdx, int src, int srcA, InkMode ink) {
        if (dstIdx < 0 || dstIdx >= argb.length) return;
        int dst = argb[dstIdx];

        int srcR = (src >> 16) & 0xFF;
        int srcG = (src >> 8) & 0xFF;
        int srcB = src & 0xFF;
        int dstR = (dst >> 16) & 0xFF;
        int dstG = (dst >> 8) & 0xFF;
        int dstB = dst & 0xFF;

        int outR, outG, outB;

        switch (ink) {
            case ADD_PIN -> {
                outR = Math.min(255, dstR + srcR);
                outG = Math.min(255, dstG + srcG);
                outB = Math.min(255, dstB + srcB);
            }
            case ADD -> {
                outR = (dstR + srcR) & 0xFF;
                outG = (dstG + srcG) & 0xFF;
                outB = (dstB + srcB) & 0xFF;
            }
            case SUBTRACT_PIN -> {
                outR = Math.max(0, dstR - srcR);
                outG = Math.max(0, dstG - srcG);
                outB = Math.max(0, dstB - srcB);
            }
            case SUBTRACT -> {
                outR = (dstR - srcR) & 0xFF;
                outG = (dstG - srcG) & 0xFF;
                outB = (dstB - srcB) & 0xFF;
            }
            case DARKEST -> {
                outR = Math.min(dstR, srcR);
                outG = Math.min(dstG, srcG);
                outB = Math.min(dstB, srcB);
            }
            case LIGHTEN, LIGHTEST -> {
                outR = Math.max(dstR, srcR);
                outG = Math.max(dstG, srcG);
                outB = Math.max(dstB, srcB);
            }
            case REVERSE -> {
                // XOR: src ^ dst
                outR = srcR ^ dstR;
                outG = srcG ^ dstG;
                outB = srcB ^ dstB;
            }
            case GHOST -> {
                // AND(~src, dst)
                outR = (~srcR & 0xFF) & dstR;
                outG = (~srcG & 0xFF) & dstG;
                outB = (~srcB & 0xFF) & dstB;
            }
            case NOT_COPY -> {
                // Invert source
                outR = ~srcR & 0xFF;
                outG = ~srcG & 0xFF;
                outB = ~srcB & 0xFF;
            }
            case NOT_TRANSPARENT -> {
                // AND(src, dst)
                outR = srcR & dstR;
                outG = srcG & dstG;
                outB = srcB & dstB;
            }
            case NOT_REVERSE -> {
                // XOR(~src, dst)
                outR = (~srcR & 0xFF) ^ dstR;
                outG = (~srcG & 0xFF) ^ dstG;
                outB = (~srcB & 0xFF) ^ dstB;
            }
            case NOT_GHOST -> {
                // OR(~src, dst)
                outR = (~srcR & 0xFF) | dstR;
                outG = (~srcG & 0xFF) | dstG;
                outB = (~srcB & 0xFF) | dstB;
            }
            default -> {
                alphaComposite(argb, dstIdx, src, srcA);
                return;
            }
        }

        // Apply blend (srcA) as interpolation between dst and result
        if (srcA < 255) {
            int invA = 255 - srcA;
            outR = (outR * srcA + dstR * invA) / 255;
            outG = (outG * srcA + dstG * invA) / 255;
            outB = (outB * srcA + dstB * invA) / 255;
        }

        argb[dstIdx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
    }

    private static void alphaComposite(int[] argb, int dstIdx, int src, int srcA) {
        if (dstIdx < 0 || dstIdx >= argb.length) return;
        int dst = argb[dstIdx];
        int dstA = (dst >> 24) & 0xFF;
        int invA = 255 - srcA;

        int outA = srcA + (dstA * invA / 255);
        if (outA == 0) {
            argb[dstIdx] = 0;
            return;
        }

        int srcR = (src >> 16) & 0xFF;
        int srcG = (src >> 8) & 0xFF;
        int srcB = src & 0xFF;
        int dstR = (dst >> 16) & 0xFF;
        int dstG = (dst >> 8) & 0xFF;
        int dstB = dst & 0xFF;

        int outR = (srcR * srcA + dstR * dstA * invA / 255) / outA;
        int outG = (srcG * srcA + dstG * dstA * invA / 255) / outA;
        int outB = (srcB * srcA + dstB * dstA * invA / 255) / outA;

        argb[dstIdx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }

    private static void alphaCompositePercent(int[] argb, int dstIdx, int src, int srcA, int blendPercent) {
        if (dstIdx < 0 || dstIdx >= argb.length) return;
        if (srcA <= 0 || blendPercent <= 0) return;
        if (blendPercent >= 100) {
            alphaComposite(argb, dstIdx, src, srcA);
            return;
        }

        int dst = argb[dstIdx];
        int dstA = (dst >> 24) & 0xFF;
        if (dstA != 255) {
            int blendedAlpha = (srcA * blendPercent) / 100;
            alphaComposite(argb, dstIdx, src, blendedAlpha);
            return;
        }

        int opacity = (srcA * blendPercent) / 100;
        int invOpacity = 256 - opacity;

        int srcR = (src >> 16) & 0xFF;
        int srcG = (src >> 8) & 0xFF;
        int srcB = src & 0xFF;
        int dstR = (dst >> 16) & 0xFF;
        int dstG = (dst >> 8) & 0xFF;
        int dstB = dst & 0xFF;

        int outR = (srcR * opacity + dstR * invOpacity) >> 8;
        int outG = (srcG * opacity + dstG * invOpacity) >> 8;
        int outB = (srcB * opacity + dstB * invOpacity) >> 8;

        argb[dstIdx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
    }
}
