package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.InkMode;

import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastMember;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches ink-processed bitmaps for rendering.
 * All decoding is synchronous — WASM is single-threaded and desktop bitmaps decode fast enough.
 */
public class BitmapCache {

    record IndexedMatteColorRemap(int foreColor, int backColor) {}

    private final Map<CacheKey, Bitmap> cache = new ConcurrentHashMap<>();
    private final Map<DynamicCacheKey, Bitmap> dynamicCache = new ConcurrentHashMap<>();
    private final Set<MemberCacheId> decodeFailed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Tracks the last known palette version per member ID to detect palette changes. */
    private final Map<MemberCacheId, Integer> paletteVersions = new ConcurrentHashMap<>();

    private record MemberCacheId(int fileIdentity, int memberId) {}

    private record CacheKey(MemberCacheId member, Palette paletteOverride,
                            int ink, int foreColor, int backColor,
                            boolean hasForeColor, boolean hasBackColor) {}

    private record DynamicCacheKey(Bitmap bitmap, int mutationRevision,
                                   Palette imagePalette, byte[] paletteIndices,
                                   int paletteRefCastLib, int paletteRefMemberNum,
                                   String paletteRefSystemName, boolean nativeAlpha,
                                   int ink, int foreColor, int backColor,
                                   boolean hasForeColor, boolean hasBackColor,
                                   boolean neutralizeOpaqueWhite) {}

    /**
     * Get an ink-processed bitmap for a file-loaded cast member.
     * Decodes synchronously on first call; returns cached bitmap on subsequent calls.
     */
    public Bitmap getProcessed(CastMemberChunk member, int ink, int backColor, Player player) {
        return getProcessed(member, ink, backColor, 0, false, false, player, null);
    }

    /**
     * Get an ink-processed bitmap with an optional palette override.
     * Used for palette swap animation where the runtime palette differs from the embedded one.
     */
    public Bitmap getProcessed(CastMemberChunk member, int ink, int backColor, Player player, Palette paletteOverride) {
        return getProcessed(member, ink, backColor, 0, false, false, player, paletteOverride);
    }

    /**
     * Get an ink-processed bitmap with foreColor/backColor colorization.
     * For 1-bit bitmaps, Director applies foreColor/backColor colorization BEFORE ink
     * processing. This ensures that masks with foreColor=white become fully white before
     * BLEND/MATTE ink removes the white background, making them transparent.
     */
    public Bitmap getProcessed(CastMemberChunk member, int ink, int backColor,
                                int foreColor, boolean hasForeColor, boolean hasBackColor,
                                Player player, Palette paletteOverride) {
        MemberCacheId memberId = memberKey(member);
        CacheKey key = new CacheKey(memberId, paletteOverride, ink,
                hasForeColor ? foreColor : 0, backColor, hasForeColor, hasBackColor);

        Bitmap cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        if (player == null || decodeFailed.contains(memberId)) {
            return null;
        }

        try {
            // Parse BitmapInfo for useAlpha and paletteId.
            // Do not resolve the default palette here. BitmapResolver handles
            // cross-file palette references for external casts; resolving through
            // member.file() first can fall back to System Mac before the real
            // palette in the main movie or another cast is considered.
            boolean useAlpha = false;
            if (member.specificData() != null && member.specificData().length >= 10) {
                DirectorFile memberFile = member.file();
                int dirVer = 1200;
                if (memberFile != null && memberFile.getConfig() != null) {
                    dirVer = memberFile.getConfig().directorVersion();
                }
                BitmapInfo info = BitmapInfo.parse(member.specificData(), dirVer);
                useAlpha = info.useAlpha();
            }

            // Decode with the explicit override only for paletteRef. Otherwise
            // let BitmapResolver choose the authored palette, including cross-file
            // palette refs in the main movie and loaded external casts.
            Optional<Bitmap> bitmap;
            DirectorFile.clearJpegDecodePending();
            if (paletteOverride != null) {
                bitmap = player.getBitmapResolver().decodeBitmap(member, paletteOverride);
            } else {
                bitmap = player.getBitmapResolver().decodeBitmap(member);
            }
            if (bitmap.isEmpty()) {
                if (DirectorFile.consumeJpegDecodePending()) {
                    return null;
                }
                decodeFailed.add(memberId);
                return null;
            }

            Bitmap raw = bitmap.get();
            raw = coerceNonNativeAlphaToOpaque(raw, useAlpha);
            Palette palette = raw.getImagePalette() != null ? raw.getImagePalette() : paletteOverride;

            // Director applies foreColor/backColor colorization BEFORE ink processing
            // for 1-bit bitmaps. This is critical for masks: a mask with foreColor=white
            // should become all-white, then BLEND/MATTE ink removes the white background
            // making the mask fully transparent.
            if (raw.getBitDepth() <= 1 && hasForeColor) {
                raw = InkProcessor.applyForeColorRemap(raw, foreColor, backColor);
            }

            Bitmap processed = applyIndexedMatteColorRemapIfNeeded(
                    raw,
                    applySpriteInk(raw, ink, backColor, hasBackColor, useAlpha, palette, false),
                    ink, foreColor, backColor, hasForeColor, hasBackColor, palette);
            cache.put(key, processed);
            return processed;
        } catch (Exception e) {
            decodeFailed.add(memberId);
            return null;
        }
    }

    /**
     * Invalidate cache entries for a member if its palette version has changed.
     * Returns true if the cache was actually invalidated (palette changed since last render).
     */
    public boolean invalidateIfPaletteChanged(CastMemberChunk member, int paletteVersion) {
        MemberCacheId memberId = memberKey(member);
        Integer lastVersion = paletteVersions.get(memberId);
        if (lastVersion != null && lastVersion == paletteVersion) {
            return false; // No change
        }
        paletteVersions.put(memberId, paletteVersion);
        // Remove from caches - scan for any key containing this member ID
        cache.keySet().removeIf(key -> key.member().equals(memberId));
        decodeFailed.remove(memberId);
        return true;
    }

    /**
     * Get an ink-processed bitmap for a dynamic (runtime-created) cast member.
     * Synchronous — dynamic members already have their bitmap decoded.
     * Not cached because generic runtime-created members can be mutable without
     * going through Lingo image mutation dispatch.
     */
    public Bitmap getProcessedDynamic(CastMember dynMember, int ink, int backColor,
                                     int foreColor, boolean hasForeColor, boolean hasBackColor) {
        Bitmap bmp = dynMember != null ? dynMember.getBitmap() : null;
        if (bmp == null) {
            return null;
        }

        if (InkProcessor.shouldProcessInk(ink)) {
            boolean useAlpha = bmp.getBitDepth() == 32 && bmp.isNativeAlpha();
            return applyIndexedMatteColorRemapIfNeeded(
                    bmp,
                    applySpriteInk(bmp, ink, backColor, hasBackColor,
                            useAlpha, bmp.getImagePalette(), false),
                    ink, foreColor, backColor, hasForeColor, hasBackColor, bmp.getImagePalette());
        }
        return applyIndexedMatteColorRemapIfNeeded(
                bmp,
                bmp,
                ink, foreColor, backColor, hasForeColor, hasBackColor, bmp.getImagePalette());
    }

    public Bitmap getProcessedScriptModifiedDynamic(CastMember dynMember, int ink, int backColor,
                                                    int foreColor, boolean hasForeColor,
                                                    boolean hasBackColor,
                                                    boolean neutralizeOpaqueWhite) {
        Bitmap bmp = dynMember != null ? dynMember.getBitmap() : null;
        if (bmp == null) {
            return null;
        }

        DynamicCacheKey key = dynamicKey(bmp, ink, backColor, foreColor,
                hasForeColor, hasBackColor, neutralizeOpaqueWhite);
        Bitmap cached = dynamicCache.get(key);
        if (cached != null) {
            return cached;
        }
        removeStaleDynamicEntries(bmp);

        Bitmap source = bmp;
        if (source.getBitDepth() <= 1 && hasForeColor) {
            source = InkProcessor.applyForeColorRemap(source, foreColor, backColor);
        }

        Bitmap processed;
        if (InkProcessor.shouldProcessInk(ink)) {
            Bitmap inkSource = source;
            if (neutralizeOpaqueWhite) {
                inkSource = InkProcessor.convertOpaqueWhiteToTransparent(inkSource);
            }
            boolean hasNativeAlpha = inkSource.getBitDepth() == 32 && inkSource.isNativeAlpha();
            processed = applyIndexedMatteColorRemapIfNeeded(
                    bmp,
                    applySpriteInk(inkSource, ink, backColor, hasBackColor,
                            hasNativeAlpha, inkSource.getImagePalette(), true),
                    ink, foreColor, backColor, hasForeColor, hasBackColor, bmp.getImagePalette());
        } else {
            processed = applyIndexedMatteColorRemapIfNeeded(
                    bmp,
                    source,
                    ink, foreColor, backColor, hasForeColor, hasBackColor, bmp.getImagePalette());
        }
        dynamicCache.put(key, processed);
        return processed;
    }

    static Bitmap coerceNonNativeAlphaToOpaque(Bitmap raw, boolean useAlpha) {
        if (raw == null || raw.getBitDepth() != 32 || useAlpha || !raw.hasTransparentPixels()) {
            return raw;
        }
        return raw.copyWithNonNativeAlphaOpaque();
    }

    private static Bitmap applySpriteInk(Bitmap raw, int ink, int backColor, boolean hasBackColor,
                                         boolean useAlpha, Palette palette, boolean skipGraduatedAlpha) {
        Bitmap processed = InkProcessor.applyInk(raw, ink, backColor, useAlpha, palette, skipGraduatedAlpha);
        if (shouldUseImplicitIndexedBackground(raw, processed, ink, hasBackColor)) {
            Bitmap indexed = InkProcessor.applyImplicitIndexedBackgroundTransparent(raw);
            if (introducesTransparency(raw, indexed)) {
                return indexed;
            }
        }
        return processed;
    }

    private static boolean shouldUseImplicitIndexedBackground(Bitmap raw, Bitmap processed,
                                                              int ink, boolean hasBackColor) {
        return InkMode.fromCode(ink) == InkMode.BACKGROUND_TRANSPARENT
                && !hasBackColor
                && raw != null
                && !raw.isScriptModified()
                && raw.getPaletteIndicesUnsafe() != null
                && !introducesTransparency(raw, processed);
    }

    private static boolean introducesTransparency(Bitmap before, Bitmap after) {
        if (before == null || after == null) {
            return false;
        }
        int count = Math.min(before.getPixels().length, after.getPixels().length);
        for (int i = 0; i < count; i++) {
            if ((before.getPixels()[i] >>> 24) != 0 && (after.getPixels()[i] >>> 24) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clear all cached bitmaps. Call when external casts are loaded.
     */
    public void clear() {
        cache.clear();
        dynamicCache.clear();
        decodeFailed.clear();
        paletteVersions.clear();
    }

    private DynamicCacheKey dynamicKey(Bitmap bitmap, int ink, int backColor,
                                       int foreColor, boolean hasForeColor,
                                       boolean hasBackColor,
                                       boolean neutralizeOpaqueWhite) {
        return new DynamicCacheKey(
                bitmap,
                bitmap.getMutationRevision(),
                bitmap.getImagePalette(),
                bitmap.getPaletteIndicesUnsafe(),
                bitmap.getPaletteRefCastLib(),
                bitmap.getPaletteRefMemberNum(),
                bitmap.getPaletteRefSystemName(),
                bitmap.isNativeAlpha(),
                ink,
                hasForeColor ? foreColor : 0,
                backColor,
                hasForeColor,
                hasBackColor,
                neutralizeOpaqueWhite
        );
    }

    private void removeStaleDynamicEntries(Bitmap bitmap) {
        dynamicCache.keySet().removeIf(key -> key.bitmap() == bitmap && !matchesCurrentBitmapState(key, bitmap));
    }

    private boolean matchesCurrentBitmapState(DynamicCacheKey key, Bitmap bitmap) {
        return key.mutationRevision() == bitmap.getMutationRevision()
                && key.imagePalette() == bitmap.getImagePalette()
                && key.paletteIndices() == bitmap.getPaletteIndicesUnsafe()
                && key.paletteRefCastLib() == bitmap.getPaletteRefCastLib()
                && key.paletteRefMemberNum() == bitmap.getPaletteRefMemberNum()
                && Objects.equals(key.paletteRefSystemName(), bitmap.getPaletteRefSystemName())
                && key.nativeAlpha() == bitmap.isNativeAlpha();
    }

    private MemberCacheId memberKey(CastMemberChunk member) {
        int fileIdentity = member != null && member.file() != null ? System.identityHashCode(member.file()) : 0;
        int memberId = member != null ? member.id().value() : 0;
        return new MemberCacheId(fileIdentity, memberId);
    }

    static IndexedMatteColorRemap resolveIndexedMatteColorRemap(
            Bitmap raw, int ink, int foreColor, int backColor,
            boolean hasForeColor, boolean hasBackColor, Palette palette) {
        if (raw == null || raw.getBitDepth() <= 1 || raw.getPaletteIndicesUnsafe() == null) {
            return null;
        }
        InkMode inkMode = InkMode.fromCode(ink);
        if (inkMode != InkMode.MATTE) {
            return null;
        }
        if (!hasForeColor && !hasBackColor) {
            return null;
        }
        int effectiveForeColor = hasForeColor ? (foreColor & 0xFFFFFF) : 0x000000;
        int effectiveBackColor = hasBackColor
                ? InkProcessor.resolveBackColor(raw, InkMode.COPY, backColor, false, palette)
                : 0xFFFFFF;

        // Skip the default black→white identity ramp. Dynamic sprites inherit score defaults
        // as explicit colors, but Director only needs indexed MATTE recoloring when a script
        // actually changes the palette ramp.
        if (effectiveForeColor == 0x000000 && effectiveBackColor == 0xFFFFFF) {
            return null;
        }
        return new IndexedMatteColorRemap(effectiveForeColor, effectiveBackColor);
    }

    static Bitmap applyIndexedMatteColorRemapIfNeeded(
            Bitmap raw, Bitmap processed, int ink, int foreColor, int backColor,
            boolean hasForeColor, boolean hasBackColor, Palette palette) {
        return applyIndexedMatteColorRemap(
                raw,
                processed,
                resolveIndexedMatteColorRemap(
                        raw, ink, foreColor, backColor, hasForeColor, hasBackColor, palette));
    }

    static Bitmap applyIndexedMatteColorRemap(Bitmap raw, Bitmap processed, IndexedMatteColorRemap remap) {
        if (raw == null || processed == null || remap == null) {
            return processed;
        }
        return InkProcessor.applyIndexedColorRemap(raw, processed, remap.foreColor(), remap.backColor());
    }
}
