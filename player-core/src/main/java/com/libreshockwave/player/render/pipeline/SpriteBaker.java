package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Drawing;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.ShapeInfo;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.sprite.SpriteColorSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized sprite-to-bitmap baking pipeline.
 * Converts all sprite types (BITMAP, TEXT, SHAPE) into pre-rendered bitmaps
 * so renderers only need to blit pixels — no type-specific draw logic needed.
 */
public class SpriteBaker {

    private final BitmapCache bitmapCache;
    private final CastLibManager castLibManager;
    private final Player player;
    private final List<SpriteBakeStep> bakeSteps = new ArrayList<>();
    private final Map<ShapeCacheKey, Bitmap> shapeCache = new HashMap<>();
    private int animationTick;
    private int renderRevision;
    private boolean animatedContentBaked;
    private Palette frameActivePalette;

    private record ShapeCacheKey(int castFileIdentity, int castMemberId,
                                 int dynamicMemberIdentity, int width, int height,
                                 int foreColor, int backColor, int ink, int blend,
                                 int shapeDataHash) {}

    public SpriteBaker(BitmapCache bitmapCache, CastLibManager castLibManager, Player player) {
        this.bitmapCache = bitmapCache;
        this.castLibManager = castLibManager;
        this.player = player;
        registerDefaultSteps();
    }

    public int getRenderRevision() {
        return renderRevision;
    }

    /**
     * Bake all sprites in the list, returning a new list with baked bitmaps attached.
     */
    public List<RenderSprite> bakeSprites(List<RenderSprite> sprites) {
        animationTick++;
        animatedContentBaked = false;
        frameActivePalette = resolveFrameActivePalette(sprites);
        List<RenderSprite> result = new ArrayList<>(sprites.size());
        try {
            for (RenderSprite sprite : sprites) {
                result.add(bake(sprite));
            }
        } finally {
            frameActivePalette = null;
        }
        if (animatedContentBaked) {
            renderRevision++;
        }
        return result;
    }

    /**
     * Bake a single sprite: dispatch by type, apply colorization, attach bitmap.
     */
    public RenderSprite bake(RenderSprite sprite) {
        Bitmap baked = null;
        for (SpriteBakeStep step : bakeSteps) {
            if (step.supports(sprite)) {
                baked = step.bake(sprite);
                break;
            }
        }

        // Apply Director's sprite-level foreColor/backColor colorization.
        int ch = sprite.getChannel();
        boolean hasColor = sprite.hasForeColor() || sprite.hasBackColor();
        boolean colorizeOk = InkProcessor.allowsColorize(sprite.getInk());
        boolean scriptMod = isScriptModifiedSprite(sprite);
        if (baked != null && sprite.getType() != RenderSprite.SpriteType.SHAPE && hasColor && colorizeOk) {
            if (scriptMod) {
                int bgColor = sprite.getBackColor() & 0xFFFFFF;
                if (sprite.hasBackColor() && bgColor != 0xFFFFFF
                        && shouldApplyBackColorToScriptBitmap(sprite)) {
                    baked = InkProcessor.remapExactColor(baked, 0xFFFFFF, bgColor);
                }
            }
            // Note: foreColor/backColor colorization for 1-bit file-loaded bitmaps
            // is now handled inside BitmapCache.getProcessed(), BEFORE ink processing.
            // This ordering is critical for masks: a mask with foreColor=white becomes
            // all-white, then BLEND/MATTE ink removes the white background, making
            // the mask fully transparent (as Director intends).
        }

        // For text sprites, the baked bitmap may have different dimensions than the
        // score sprite (member specificData dimensions vs score stored dimensions).
        // Update the sprite dimensions to match the baked bitmap so the renderer
        // doesn't scale or clip the text.
        if (baked != null && (sprite.getType() == RenderSprite.SpriteType.TEXT
                || sprite.getType() == RenderSprite.SpriteType.BUTTON)
                && (baked.getWidth() != sprite.getWidth() || baked.getHeight() != sprite.getHeight())) {
            return sprite.withBakedBitmapAndSize(baked, baked.getWidth(), baked.getHeight());
        }

        return sprite.withBakedBitmap(baked);
    }

    private boolean isScriptModifiedSprite(RenderSprite sprite) {
        if (sprite.getDynamicMember() == null) return false;
        Bitmap bmp = sprite.getDynamicMember().getBitmap();
        if (bmp != null && bmp.isScriptModified()) {
            return true;
        }
        Bitmap textImage = sprite.getDynamicMember().getScriptModifiedTextImage();
        return textImage != null && textImage.isScriptModified();
    }

    private boolean shouldApplyBackColorToScriptBitmap(RenderSprite sprite) {
        if (sprite == null || sprite.getDynamicMember() == null) {
            return false;
        }
        Bitmap raw = sprite.getDynamicMember().getBitmap();
        return raw != null && raw.getBitDepth() <= 1;
    }

    private boolean shouldNeutralizeOpaqueWhiteForScriptCanvas(RenderSprite sprite, Bitmap bmp) {
        if (sprite == null || bmp == null) {
            return false;
        }
        if (bmp.getBitDepth() != 32 || bmp.isNativeAlpha()) {
            return false;
        }
        InkMode ink = sprite.getInkMode();
        if (ink != InkMode.DARKEN && ink != InkMode.LIGHTEN) {
            return false;
        }
        return bmp.isScriptModified();
    }

    private void registerDefaultSteps() {
        registerBakeStep(new BitmapSpriteBakeStep());
        registerBakeStep(new TextSpriteBakeStep());
        registerBakeStep(new ShapeSpriteBakeStep());
        registerBakeStep(new FilmLoopSpriteBakeStep());
        registerBakeStep(new UnsupportedSpriteBakeStep());
    }

    public void registerBakeStep(SpriteBakeStep step) {
        bakeSteps.add(step);
    }

    public interface SpriteBakeStep {
        boolean supports(RenderSprite sprite);
        Bitmap bake(RenderSprite sprite);
    }

    private final class BitmapSpriteBakeStep implements SpriteBakeStep {
        @Override
        public boolean supports(RenderSprite sprite) {
            return sprite.getType() == RenderSprite.SpriteType.BITMAP;
        }

        @Override
        public Bitmap bake(RenderSprite sprite) {
            return bakeBitmap(sprite);
        }
    }

    private final class TextSpriteBakeStep implements SpriteBakeStep {
        @Override
        public boolean supports(RenderSprite sprite) {
            return sprite.getType() == RenderSprite.SpriteType.TEXT
                    || sprite.getType() == RenderSprite.SpriteType.BUTTON;
        }

        @Override
        public Bitmap bake(RenderSprite sprite) {
            return bakeText(sprite);
        }
    }

    private final class ShapeSpriteBakeStep implements SpriteBakeStep {
        @Override
        public boolean supports(RenderSprite sprite) {
            return sprite.getType() == RenderSprite.SpriteType.SHAPE;
        }

        @Override
        public Bitmap bake(RenderSprite sprite) {
            return bakeShape(sprite);
        }
    }

    private final class FilmLoopSpriteBakeStep implements SpriteBakeStep {
        @Override
        public boolean supports(RenderSprite sprite) {
            return sprite.getType() == RenderSprite.SpriteType.FILM_LOOP;
        }

        @Override
        public Bitmap bake(RenderSprite sprite) {
            return bakeFilmLoop(sprite);
        }
    }

    private static final class UnsupportedSpriteBakeStep implements SpriteBakeStep {
        @Override
        public boolean supports(RenderSprite sprite) {
            return true;
        }

        @Override
        public Bitmap bake(RenderSprite sprite) {
            return null;
        }
    }

    /**
     * Bake a BITMAP sprite: decode + ink-process via BitmapCache.
     */
    private Bitmap bakeBitmap(RenderSprite sprite) {
        Bitmap b = null;
        CastMember liveMember = resolveRuntimeBitmapMember(sprite);

        // Check if the runtime CastMember's bitmap was modified by Lingo (fill, copyPixels, etc.)
        // If so, use the live bitmap directly instead of the stale BitmapCache entry.
        if (liveMember != null) {
            Bitmap liveBmp = liveMember.getBitmap();
            if (liveBmp != null && liveBmp.isScriptModified()) {
                return bitmapCache.getProcessedScriptModifiedDynamic(
                        liveMember,
                        sprite.getInk(),
                        sprite.getBackColor(),
                        sprite.getBackColorSource(),
                        sprite.getForeColor(),
                        sprite.getForeColorSource(),
                        sprite.hasForeColor(),
                        sprite.hasBackColor(),
                        shouldNeutralizeOpaqueWhiteForScriptCanvas(sprite, liveBmp));
            }
        }

        if (sprite.getCastMember() != null) {
            // Check for runtime palette override (palette swap animation)
            PaletteOverrideInfo palInfo = resolvePaletteOverride(sprite);
            Palette paletteOverride = null;
            if (palInfo != null) {
                // Only invalidate cache when palette actually changed
                bitmapCache.invalidateIfPaletteChanged(sprite.getCastMember(), palInfo.version);
                paletteOverride = palInfo.palette;
            } else if (shouldUseFrameActivePalette(sprite.getCastMember())) {
                paletteOverride = frameActivePalette;
            }
            b = bitmapCache.getProcessed(sprite.getCastMember(), sprite.getInk(),
                    sprite.getBackColor(),
                    sprite.getBackColorSource(),
                    sprite.getForeColor(), sprite.getForeColorSource(),
                    sprite.hasForeColor(), sprite.hasBackColor(),
                    player, paletteOverride);
        }
        if (b == null && sprite.getDynamicMember() != null) {
            b = bitmapCache.getProcessedDynamic(sprite.getDynamicMember(),
                    sprite.getInk(), sprite.getBackColor(), sprite.getBackColorSource(),
                    sprite.getForeColor(), sprite.getForeColorSource(),
                    sprite.hasForeColor(), sprite.hasBackColor());
        }
        return b;
    }

    private Palette resolveFrameActivePalette(List<RenderSprite> sprites) {
        if (player == null || player.getBitmapResolver() == null) {
            return null;
        }

        Palette current = com.libreshockwave.vm.datum.Datum.isPuppetPaletteActive()
                ? com.libreshockwave.vm.datum.Datum.getPuppetPalette()
                : player.getBitmapResolver().getMoviePalette();
        if (com.libreshockwave.vm.datum.Datum.isPuppetPaletteActive()) {
            return current;
        }

        if (current != null && !isSystemPalette(current)) {
            return current;
        }

        Palette authoredFramePalette = resolveFirstVisibleAuthoredPalette(sprites);
        if (authoredFramePalette != null) {
            return authoredFramePalette;
        }

        return current;
    }

    private Palette resolveFirstVisibleAuthoredPalette(List<RenderSprite> sprites) {
        if (sprites == null) {
            return null;
        }
        for (RenderSprite sprite : sprites) {
            if (sprite == null || !sprite.isVisible()
                    || sprite.getType() != RenderSprite.SpriteType.BITMAP
                    || sprite.getCastMember() == null) {
                continue;
            }
            Palette palette = player.getBitmapResolver().resolveAuthoredPalette(sprite.getCastMember());
            if (palette == null || isSystemPalette(palette)) {
                continue;
            }
            return palette;
        }
        return null;
    }

    private static boolean isSystemPalette(Palette palette) {
        if (palette == null || palette.getName() == null) {
            return false;
        }
        String name = palette.getName();
        return "System - Mac".equalsIgnoreCase(name)
                || "System - Windows".equalsIgnoreCase(name);
    }

    private boolean shouldUseFrameActivePalette(com.libreshockwave.chunks.CastMemberChunk member) {
        return frameActivePalette != null
                && player != null
                && player.getBitmapResolver() != null
                && player.getBitmapResolver().usesCurrentPalette(member);
    }

    /**
     * Resolve the runtime CastMember wrapper for a bitmap sprite.
     *
     * Score-placed sprites still need this lookup because authored sprites can
     * have their member.image mutated at runtime without turning into dynamic
     * sprite bindings. In that case the cast member chunk still describes the
     * authored asset, while the runtime wrapper owns the live bitmap buffer.
     */
    private CastMember resolveRuntimeBitmapMember(RenderSprite sprite) {
        if (sprite == null) {
            return null;
        }
        if (sprite.getDynamicMember() != null) {
            return sprite.getDynamicMember();
        }
        if (castLibManager == null || sprite.getCastMember() == null) {
            return null;
        }
        return castLibManager.findRuntimeMember(sprite.getCastMember());
    }

    private boolean hasBorderColor(Bitmap bmp, int colorRgb) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        for (int x = 0; x < w; x++) {
            if ((bmp.getPixel(x, 0) & 0xFFFFFF) == colorRgb) return true;
            if ((bmp.getPixel(x, h - 1) & 0xFFFFFF) == colorRgb) return true;
        }
        for (int y = 1; y < h - 1; y++) {
            if ((bmp.getPixel(0, y) & 0xFFFFFF) == colorRgb) return true;
            if ((bmp.getPixel(w - 1, y) & 0xFFFFFF) == colorRgb) return true;
        }
        return false;
    }

    private record PaletteOverrideInfo(Palette palette, int version) {}

    /**
     * Resolve a palette override for a sprite's bitmap member.
     * Returns null if no palette override is set.
     */
    private PaletteOverrideInfo resolvePaletteOverride(RenderSprite sprite) {
        if (castLibManager == null || sprite.getCastMember() == null) {
            return null;
        }

        CastMember member = null;
        if (player != null) {
            com.libreshockwave.player.sprite.SpriteState spriteState =
                    player.getStageRenderer().getSpriteRegistry().get(sprite.getChannel());
            if (spriteState != null) {
                int castLibNum = spriteState.getEffectiveCastLib();
                int memberNum = spriteState.getEffectiveCastMember();
                if (castLibNum > 0 && memberNum > 0) {
                    member = castLibManager.getDynamicMember(castLibNum, memberNum);
                }
            }
        }
        if (member == null) {
            member = castLibManager.findRuntimeMember(sprite.getCastMember());
        }
        if (member == null || !member.hasPaletteOverride()) {
            return null;
        }

        Palette palette = member.getRuntimePaletteOverride();
        if (palette != null) {
            return new PaletteOverrideInfo(palette, member.getPaletteVersion());
        }

        String systemName = member.getPaletteRefSystemName();
        if (systemName != null && !systemName.isEmpty()) {
            palette = Palette.getBuiltInBySymbolName(systemName);
            if (palette != null) {
                return new PaletteOverrideInfo(palette, member.getPaletteVersion());
            }
        }

        // Resolve the palette member to a Palette object
        int palCastLib = member.getPaletteRefCastLib();
        int palMemberNum = member.getPaletteRefMemberNum();

        palette = castLibManager.resolvePaletteByMember(palCastLib, palMemberNum);
        return palette != null ? new PaletteOverrideInfo(palette, member.getPaletteVersion()) : null;
    }

    /**
     * Bake a TEXT or BUTTON sprite: resolve member, render text to bitmap.
     */
    private Bitmap bakeText(RenderSprite sprite) {
        // Try dynamic member first (runtime-created text/field members)
        CastMember member = sprite.getDynamicMember();

        // Fall back to file-loaded member lookup by name
        if (member == null && sprite.getCastMember() != null && castLibManager != null) {
            String memberName = sprite.getCastMember().name();
            if (memberName != null && !memberName.isEmpty()) {
                member = castLibManager.findCastMemberByName(memberName);
            }
        }

        // Fall back to runtime member lookup by chunk ID (for nameless members
        // like XTRA text members whose text may have been set by Lingo)
        if (member == null && sprite.getCastMember() != null && castLibManager != null) {
            member = castLibManager.findRuntimeMember(sprite.getCastMember());
        }

        Bitmap textImage = null;

        if (member != null) {
            textImage = member.getScriptModifiedTextImage();
        }

        if (textImage == null && member != null
                && (member.hasDynamicText() || member.hasTextPresentationOverride())) {
            // Lingo can mutate a loaded text member's visual properties without
            // replacing member.text. Director renders that member through its
            // current runtime properties, not the original STXT/XMED defaults.
            int width = sprite.getWidth() > 0 ? sprite.getWidth() : 200;
            int height = sprite.getHeight() > 0 ? sprite.getHeight() : 20;
            int bgColor = dynamicTextBgColor(sprite, member);
            textImage = member.renderTextToImage(width, height, bgColor);
        }
        // For untouched file-loaded text members, fall through to bakeTextFromFile()
        // which applies the sprite's foreColor/backColor from the score.
        // member.renderTextToImage() would use the member defaults, which do not
        // reflect the score colors for that sprite channel.

        // Fall back to rendering directly from the file's STXT/XMED chunk
        // (for score-placed text sprites that don't have a runtime CastMember,
        // or when renderTextToImage returned null)
        if (textImage == null && sprite.getCastMember() != null) {
            textImage = bakeTextFromFile(sprite);
        }

        if (textImage == null) {
            return null;
        }

        // Background-transparent text is a sprite compositing concern, not a
        // mutation of member.image. Render the sprite surface with transparent
        // backing so palette-index backColors cannot leak as opaque boxes.
        if (sprite.getInkMode() == InkMode.BACKGROUND_TRANSPARENT) {
            return textImage;
        }

        // Apply ink processing
        if (InkProcessor.shouldProcessInk(sprite.getInk())) {
            textImage = InkProcessor.applyInk(textImage, sprite.getInk(),
                    sprite.getBackColor(), sprite.getBackColorSource(), false, false, null, false);
        }

        return textImage;
    }

    private int dynamicTextBgColor(RenderSprite sprite, CastMember member) {
        if (member.hasExplicitTextBgColor()) {
            int memberBgColor = member.getTextBgColor();
            if (((memberBgColor >>> 24) & 0xFF) == 0xFF
                    && (memberBgColor & 0xFFFFFF) != 0xFFFFFF) {
                return memberBgColor;
            }
        }
        if (sprite.getInkMode() == InkMode.BACKGROUND_TRANSPARENT) {
            Integer spriteBgColor = explicitRgbTextBacking(sprite);
            if (spriteBgColor != null) {
                return spriteBgColor;
            }
            return 0x00000000;
        }
        if (member.hasExplicitTextBgColor()) {
            return member.getTextBgColor();
        }
        return resolvePaletteColor(sprite.getBackColor(), sprite.getBackColorSource());
    }

    private int fileTextBgColor(RenderSprite sprite, int authoredBgColor) {
        if (sprite.getInkMode() != InkMode.BACKGROUND_TRANSPARENT) {
            return authoredBgColor;
        }
        Integer spriteBgColor = explicitRgbTextBacking(sprite);
        return spriteBgColor != null ? spriteBgColor : 0x00000000;
    }

    private Integer explicitRgbTextBacking(RenderSprite sprite) {
        if (!sprite.hasBackColor() || sprite.getBackColorSource() != SpriteColorSource.RGB) {
            return null;
        }
        int rgb = sprite.getBackColor() & 0xFFFFFF;
        return rgb != 0xFFFFFF ? 0xFF000000 | rgb : null;
    }

    /**
     * Render text directly from file data (CASt specificData + STXT/XMED chunk).
     * Used for score-placed text sprites that don't have a runtime CastMember.
     * Handles both regular STXT text and Director 7+ Text Asset Xtras (XMED chunks).
     */
    private Bitmap bakeTextFromFile(RenderSprite sprite) {
        var castMember = sprite.getCastMember();
        var file = castMember.file();
        if (file == null) return null;
        // Try XMED text first (Director 7+ Text Asset Xtra)
        if (castMember.isTextXtra()) {
            return bakeTextFromXmed(sprite, file, castMember);
        }

        // Look up the STXT chunk via KEY* table
        var textChunk = file.getTextForMember(castMember);
        if (textChunk == null || textChunk.text() == null) return null;

        // Parse text formatting from specificData
        var textInfo = com.libreshockwave.cast.TextInfo.parse(castMember.specificData());

        // Get font info from STXT formatting runs (first run determines primary font)
        String fontName = "Arial";
        int fontSize = 12;
        int fontStyle = 0;
        int runColorR = -1, runColorG = -1, runColorB = -1;
        if (!textChunk.runs().isEmpty()) {
            var run = textChunk.runs().get(0);
            fontSize = run.fontSize();
            fontStyle = run.fontStyle();
            runColorR = run.colorR();
            runColorG = run.colorG();
            runColorB = run.colorB();
        }

        String styleStr = "";
        if ((fontStyle & 1) != 0) styleStr += "bold";
        if ((fontStyle & 2) != 0) styleStr += (styleStr.isEmpty() ? "" : ",") + "italic";
        if ((fontStyle & 4) != 0) styleStr += (styleStr.isEmpty() ? "" : ",") + "underline";

        String alignment = switch (textInfo.textAlign()) {
            case 1 -> "center";
            case -1 -> "right";
            default -> "left";
        };

        int width = textInfo.width() > 0 ? textInfo.width() : sprite.getWidth();
        int height = textInfo.height() > 0 ? textInfo.height() : sprite.getHeight();
        if (width <= 0) width = 200;
        if (height <= 0) height = 20;

        var renderer = CastMember.getTextRendererStatic();
        if (renderer == null) return null;

        // Text color: prefer STXT run color, fall back to palette-resolved sprite foreColor
        int textColor;
        if (runColorR >= 0) {
            textColor = 0xFF000000 | (runColorR << 16) | (runColorG << 8) | runColorB;
        } else {
            textColor = resolvePaletteColor(sprite.getForeColor(), sprite.getForeColorSource());
        }
        int authoredBgColor = 0xFF000000 | ((textInfo.bgRed() << 16) | (textInfo.bgGreen() << 8) | textInfo.bgBlue());
        int bgColor = fileTextBgColor(sprite, authoredBgColor);

        return renderer.renderText(
                textChunk.text(), width, height,
                fontName, fontSize, styleStr,
                alignment, textColor, bgColor,
                textInfo.isWordWrap(), false,
                0, 0);
    }

    /**
     * Render text from XMED chunk data (Director 7+ Text Asset Xtra).
     * Uses XmedStyledText which contains all parsed properties from both
     * the XMED chunk and the CASt specificData — no raw byte reading needed here.
     */
    private Bitmap bakeTextFromXmed(RenderSprite sprite, com.libreshockwave.DirectorFile file,
                                     com.libreshockwave.chunks.CastMemberChunk castMember) {
        var styledText = file.getXmedStyledTextForMember(castMember);
        if (styledText == null || styledText.text() == null || styledText.text().isEmpty()) {
            return null;
        }

        // Use member's specificData dimensions (the text rect size) instead of score
        // sprite dimensions. The score stores different heights for OLE text members
        // than the Property Inspector / member intrinsic size. Director uses the member
        // dimensions for rendering text, not the score's stored height.
        int width = styledText.width() > 0 ? styledText.width() : (sprite.getWidth() > 0 ? sprite.getWidth() : 200);
        int height = styledText.height() > 0 ? styledText.height() : (sprite.getHeight() > 0 ? sprite.getHeight() : 20);

        // ARGB format — text color from XMED data if available, fall back to palette-resolved foreColor
        int textColor;
        if (styledText.colorR() >= 0) {
            textColor = styledText.textColorARGB();
        } else {
            textColor = resolvePaletteColor(sprite.getForeColor(), sprite.getForeColorSource());
        }
        int bgColor = fileTextBgColor(sprite, resolvePaletteColor(sprite.getBackColor(), sprite.getBackColorSource()));

        var renderer = CastMember.getTextRendererStatic();
        if (renderer == null) return null;

        String styleStr = styledText.fontStyleString();

        return renderer.renderXmedText(styledText, width, height, textColor, bgColor);
    }

    /**
     * Resolve a score color value (palette index 0-255) to packed ARGB for text rendering.
     * Values > 255 are already RGB (from script-set colors).
     * Values 0-255 are palette indices looked up through the default palette.
     */
    private int resolvePaletteColor(int color) {
        return resolvePaletteColor(color, SpriteColorSource.forLegacyScoreColor(color));
    }

    private int resolvePaletteColor(int color, SpriteColorSource source) {
        if (source == SpriteColorSource.RGB || color > 255) {
            // Already packed RGB (script-set)
            return 0xFF000000 | (color & 0xFFFFFF);
        }
        // Palette index lookup
        Palette palette = player != null && player.getFile() != null
                ? player.getFile().resolvePalette(-1) : null;
        if (palette != null) {
            int index = source == SpriteColorSource.PALETTE_INDEX
                    ? color & 0xFF
                    : 255 - (color & 0xFF);
            return 0xFF000000 | (palette.getColor(index) & 0xFFFFFF);
        }
        int rgb = InkProcessor.resolveSpriteColor(color, source, null);
        return 0xFF000000 | rgb;
    }

    /**
     * Bake a FILM_LOOP sprite: render the last frame of the film loop.
     * Film loops contain embedded score data with sub-sprites positioned in
     * stage coordinates. The specificData stores a bounding rect that defines
     * the film loop's coordinate space.
     */
    private Bitmap bakeFilmLoop(RenderSprite sprite) {
        var castMember = sprite.getCastMember();
        if (castMember == null || castMember.file() == null) return null;

        var file = castMember.file();

        // Look up the embedded score chunk via KEY* table
        ScoreChunk embeddedScore = file.getScoreForMember(castMember);
        if (embeddedScore == null || embeddedScore.frameData() == null) return null;

        var frameData = embeddedScore.frameData();
        if (frameData.frameChannelData().isEmpty()) return null;

        // Parse film loop rect from specificData
        var filmInfo = com.libreshockwave.cast.FilmLoopInfo.parse(castMember.specificData());
        int loopW = filmInfo.width() > 0 ? filmInfo.width() : sprite.getWidth();
        int loopH = filmInfo.height() > 0 ? filmInfo.height() : sprite.getHeight();
        if (loopW <= 0 || loopH <= 0) return null;

        // Rect origin — sub-sprites use absolute stage coords, offset by this
        int rectLeft = filmInfo.rectLeft();
        int rectTop = filmInfo.rectTop();

        // Cycle through film loop frames based on render ticks (animated marquee)
        int frameCount = frameData.header().frameCount();
        int targetFrame = (frameCount > 0) ? (animationTick % frameCount) : 0;

        // Create output bitmap filled with transparent
        int[] outPixels = new int[loopW * loopH];

        // Collect sub-sprites for the target frame
        if (frameCount > 1) {
            animatedContentBaked = true;
        }
        var subSprites = new ArrayList<ScoreChunk.FrameChannelEntry>();
        for (var entry : frameData.frameChannelData()) {
            if (entry.frameIndex().value() == targetFrame && !entry.data().isEmpty()) {
                subSprites.add(entry);
            }
        }
        subSprites.sort((a, b) -> Integer.compare(
                a.channelIndex().value(), b.channelIndex().value()));

        for (var entry : subSprites) {
            var data = entry.data();
            if (data.spriteType() == 0 || data.castMember() <= 0) continue;

            // Look up sub-sprite's cast member from the same file.
            // Film loop embedded scores use castLib=65535 (0xFFFF) or 0 as "internal cast" sentinel.
            var subMember = resolveFilmLoopMember(file, data.castLib(), data.castMember());
            if (subMember == null) continue;

            // Decode the sub-sprite's bitmap via the cache
            Bitmap subBitmap = bitmapCache.getProcessed(subMember, data.ink(),
                    data.resolvedBackColor(), SpriteColorSource.forScoreColor(data.isBackColorRGB()),
                    data.resolvedForeColor(), SpriteColorSource.forScoreColor(data.isForeColorRGB()),
                    false, false, player, null);
            if (subBitmap == null || subBitmap.getPixels() == null) continue;

            // Sub-sprite stage position minus its registration point
            int sx = data.posX();
            int sy = data.posY();
            if (subMember.isBitmap() && subMember.specificData() != null
                    && subMember.specificData().length >= 10) {
                var bi = com.libreshockwave.cast.BitmapInfo.parse(subMember);
                sx -= bi.regXLocal();
                sy -= bi.regYLocal();
            } else {
                sx -= subMember.regPointX();
                sy -= subMember.regPointY();
            }

            // Map from stage coordinates to film loop bitmap coordinates
            sx -= rectLeft;
            sy -= rectTop;

            // Blit sub-sprite onto the film loop output
            blitOnto(outPixels, loopW, loopH, subBitmap, sx, sy);
        }

        Bitmap result = new Bitmap(loopW, loopH, 32, outPixels);

        // Apply the film loop sprite's own ink to the composited bitmap.
        // Sub-sprites with COPY ink have opaque white backgrounds that need to be
        // made transparent so lower-channel sprites (e.g., the orange bar) show through.
        // useAlpha=false so 32-bit bitmaps get color-keyed (white→transparent).
        if (InkProcessor.shouldProcessInk(sprite.getInk())) {
            result = InkProcessor.applyInk(result, sprite.getInk(),
                    sprite.getBackColor(), sprite.getBackColorSource(), false, false, null, false);
        }

        return result;
    }

    /** Resolve a cast member from a film loop's embedded score. */
    private com.libreshockwave.chunks.CastMemberChunk resolveFilmLoopMember(
            DirectorFile file, int castLib, int memberNum) {
        var member = file.getCastMemberByNumber(castLib, memberNum);
        if (member == null && (castLib == 0xFFFF || castLib == 0)) {
            member = file.getCastMemberByNumber(1, memberNum);
        }
        if (member == null) {
            member = file.getCastMemberByIndex(castLib, memberNum);
            if (member == null && (castLib == 0xFFFF || castLib == 0)) {
                member = file.getCastMemberByIndex(1, memberNum);
            }
        }
        return member;
    }

    /** Blit a source bitmap onto a destination pixel array with alpha compositing. */
    private static void blitOnto(int[] dst, int dstW, int dstH, Bitmap src, int ox, int oy) {
        int[] srcPixels = src.getPixels();
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        for (int py = 0; py < srcH; py++) {
            int dy = oy + py;
            if (dy < 0 || dy >= dstH) continue;
            for (int px = 0; px < srcW; px++) {
                int dx = ox + px;
                if (dx < 0 || dx >= dstW) continue;
                int s = srcPixels[py * srcW + px];
                int sa = (s >> 24) & 0xFF;
                if (sa == 0) continue;
                int dstIdx = dy * dstW + dx;
                if (sa >= 255) {
                    dst[dstIdx] = s;
                } else {
                    int d = dst[dstIdx];
                    int da = (d >> 24) & 0xFF;
                    int inv = 255 - sa;
                    int oa = sa + (da * inv / 255);
                    if (oa > 0) {
                        int or_ = (((s >> 16) & 0xFF) * sa + ((d >> 16) & 0xFF) * da * inv / 255) / oa;
                        int og = (((s >> 8) & 0xFF) * sa + ((d >> 8) & 0xFF) * da * inv / 255) / oa;
                        int ob = ((s & 0xFF) * sa + (d & 0xFF) * da * inv / 255) / oa;
                        dst[dstIdx] = (oa << 24) | (or_ << 16) | (og << 8) | ob;
                    }
                }
            }
        }
    }

    /**
     * Bake a SHAPE sprite. Authored shape members use their Director outline/fill
     * metadata; synthetic no-member shapes still behave as solid color fills.
     */
    private Bitmap bakeShape(RenderSprite sprite) {
        ShapeCacheKey cacheKey = shapeCacheKey(sprite);
        Bitmap cached = shapeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        int w = sprite.getWidth() > 0 ? sprite.getWidth() : 50;
        int h = sprite.getHeight() > 0 ? sprite.getHeight() : 50;
        Bitmap shape = new Bitmap(w, h, 32);
        ShapeInfo shapeInfo = resolveShapeInfo(sprite);

        if (shapeInfo == null) {
            fillSolidShape(shape, sprite.getForeColor());
        } else {
            drawAuthoredShape(shape, sprite, shapeInfo);
        }

        // Apply ink processing — shapes respect ink modes just like bitmaps.
        // E.g., a shape with foreColor=white and BACKGROUND_TRANSPARENT ink
        // should be fully transparent (white pixels removed).
        if (InkProcessor.shouldProcessInk(sprite.getInk())) {
            shape = InkProcessor.applyInk(shape, sprite.getInk(),
                    sprite.getBackColor(), sprite.getBackColorSource(), false, false, null, false);
        }

        if (shapeCache.size() > 256) {
            shapeCache.clear();
        }
        shapeCache.put(cacheKey, shape);
        return shape;
    }

    private ShapeCacheKey shapeCacheKey(RenderSprite sprite) {
        int castFileIdentity = 0;
        int castMemberId = 0;
        int shapeDataHash = 0;
        if (sprite.getCastMember() != null) {
            castFileIdentity = sprite.getCastMember().file() != null
                    ? System.identityHashCode(sprite.getCastMember().file()) : 0;
            castMemberId = sprite.getCastMember().id().value();
            shapeDataHash = java.util.Arrays.hashCode(sprite.getCastMember().specificData());
        } else if (sprite.getDynamicMember() != null
                && sprite.getDynamicMember().getChunk() != null) {
            shapeDataHash = java.util.Arrays.hashCode(sprite.getDynamicMember().getChunk().specificData());
        }
        int dynamicMemberIdentity = sprite.getDynamicMember() != null
                ? System.identityHashCode(sprite.getDynamicMember()) : 0;
        int w = sprite.getWidth() > 0 ? sprite.getWidth() : 50;
        int h = sprite.getHeight() > 0 ? sprite.getHeight() : 50;
        return new ShapeCacheKey(castFileIdentity, castMemberId, dynamicMemberIdentity,
                w, h, sprite.getForeColor(), sprite.getBackColor(),
                sprite.getInk(), sprite.getBlend(), shapeDataHash);
    }

    private ShapeInfo resolveShapeInfo(RenderSprite sprite) {
        if (sprite.getCastMember() != null && sprite.getCastMember().memberType() == com.libreshockwave.cast.MemberType.SHAPE) {
            return ShapeInfo.parse(sprite.getCastMember().specificData());
        }
        if (sprite.getDynamicMember() != null
                && sprite.getDynamicMember().getChunk() != null
                && sprite.getDynamicMember().getMemberType() == com.libreshockwave.cast.MemberType.SHAPE) {
            return ShapeInfo.parse(sprite.getDynamicMember().getChunk().specificData());
        }
        return null;
    }

    private void drawAuthoredShape(Bitmap shape, RenderSprite sprite, ShapeInfo shapeInfo) {
        int argb = 0xFF000000 | (sprite.getForeColor() & 0xFFFFFF);
        if (shapeInfo.isOutlineInvisible() && shapeInfo.shapeType() != ShapeInfo.ShapeType.LINE) {
            return;
        }

        switch (shapeInfo.shapeType()) {
            case RECT, OVAL_RECT -> drawRectShape(shape, shapeInfo, argb);
            case OVAL -> drawOvalShape(shape, shapeInfo, argb);
            case LINE -> drawLineShape(shape, shapeInfo, argb);
            case UNKNOWN -> fillSolidShape(shape, sprite.getForeColor());
        }
    }

    private void drawRectShape(Bitmap shape, ShapeInfo shapeInfo, int argb) {
        if (shapeInfo.isFilled()) {
            Drawing.fillRect(shape, 0, 0, shape.getWidth(), shape.getHeight(), argb);
            return;
        }
        int strokes = Math.max(0, shapeInfo.lineThickness() - 1);
        for (int i = 0; i < strokes; i++) {
            int x = i;
            int y = i;
            int w = shape.getWidth() - (i * 2);
            int h = shape.getHeight() - (i * 2);
            if (w <= 0 || h <= 0) {
                break;
            }
            Drawing.drawRect(shape, x, y, w, h, argb);
        }
    }

    private void drawOvalShape(Bitmap shape, ShapeInfo shapeInfo, int argb) {
        int cx = shape.getWidth() / 2;
        int cy = shape.getHeight() / 2;
        int rx = Math.max(0, shape.getWidth() / 2);
        int ry = Math.max(0, shape.getHeight() / 2);

        if (shapeInfo.isFilled()) {
            Drawing.fillEllipse(shape, cx, cy, rx, ry, argb);
            return;
        }

        int strokes = Math.max(0, shapeInfo.lineThickness() - 1);
        for (int i = 0; i < strokes; i++) {
            int strokeRx = rx - i;
            int strokeRy = ry - i;
            if (strokeRx < 0 || strokeRy < 0) {
                break;
            }
            Drawing.drawEllipse(shape, cx, cy, strokeRx, strokeRy, argb);
        }
    }

    private void drawLineShape(Bitmap shape, ShapeInfo shapeInfo, int argb) {
        int strokes = Math.max(1, shapeInfo.lineThickness());
        boolean bottomToTop = shapeInfo.lineDirection() == 6;
        int startY = bottomToTop ? shape.getHeight() - 1 : 0;
        int endY = bottomToTop ? 0 : shape.getHeight() - 1;

        for (int i = 0; i < strokes; i++) {
            int y0 = Math.max(0, startY - i);
            int y1 = Math.max(0, endY - i);
            Drawing.drawLine(shape, 0, y0, Math.max(0, shape.getWidth() - 1), y1, argb);
        }
    }

    private void fillSolidShape(Bitmap shape, int rgb) {
        int argb = 0xFF000000 | (rgb & 0xFFFFFF);
        int[] pixels = shape.getPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = argb;
        }
    }

}
