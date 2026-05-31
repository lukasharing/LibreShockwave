package com.libreshockwave.player.wasm;

import com.libreshockwave.player.render.pipeline.FrameSnapshot;
import com.libreshockwave.player.render.output.SoftwareFrameRenderer;

/**
 * WASM-specific renderer that wraps the shared SoftwareFrameRenderer with
 * caching and ARGB→RGBA byte[] conversion for browser canvas ImageData.
 */
public class SoftwareRenderer {

    private int stageWidth;
    private int stageHeight;

    // Reused full-stage buffers for JS canvas output.
    private int[] argb;
    private byte[] rgba;

    // Cache: skip recomposite when stage content inputs are unchanged.
    private int lastFrame = -1;
    private int lastCastRevision = -1;
    private int lastSpriteRevision = -1;
    private int lastRenderRevision = -1;

    public SoftwareRenderer(int stageWidth, int stageHeight) {
        this.stageWidth = stageWidth;
        this.stageHeight = stageHeight;
        int pixelCount = stageWidth * stageHeight;
        this.argb = new int[pixelCount];
        this.rgba = new byte[pixelCount * 4];
    }

    /**
     * Render a FrameSnapshot into an RGBA byte[] buffer.
     * Returns the cached buffer if the frame and all render revisions are unchanged.
     */
    public byte[] render(FrameSnapshot snapshot, int castRevision, int spriteRevision) {
        int frame = snapshot.frameNumber();

        int renderRevision = snapshot.renderRevision();

        // Cache hit — return previously composited buffer
        if (frame == lastFrame && castRevision == lastCastRevision
                && spriteRevision == lastSpriteRevision
                && renderRevision == lastRenderRevision) {
            return rgba;
        }

        lastFrame = frame;
        lastCastRevision = castRevision;
        lastSpriteRevision = spriteRevision;
        lastRenderRevision = renderRevision;

        // Resize buffer if stage dimensions changed
        if (snapshot.stageWidth() != stageWidth || snapshot.stageHeight() != stageHeight) {
            stageWidth = snapshot.stageWidth();
            stageHeight = snapshot.stageHeight();
            int pixelCount = stageWidth * stageHeight;
            argb = new int[pixelCount];
            rgba = new byte[pixelCount * 4];
        }

        SoftwareFrameRenderer.renderFrameInto(snapshot, stageWidth, stageHeight, argb);
        int pixelCount = stageWidth * stageHeight;
        int safePixelCount = Math.min(pixelCount, Math.min(argb.length, rgba.length / 4));

        // Convert ARGB int[] → RGBA byte[]
        for (int i = 0; i < safePixelCount; i++) {
            int px = argb[i];
            int off = i * 4;
            rgba[off]     = (byte) ((px >> 16) & 0xFF); // R
            rgba[off + 1] = (byte) ((px >> 8) & 0xFF);  // G
            rgba[off + 2] = (byte) (px & 0xFF);          // B
            rgba[off + 3] = (byte) ((px >> 24) & 0xFF); // A
        }

        return rgba;
    }

    /** Force recomposite on next render call. */
    public void invalidate() {
        lastFrame = -1;
        lastCastRevision = -1;
        lastSpriteRevision = -1;
        lastRenderRevision = -1;
    }

    public int getWidth() { return stageWidth; }
    public int getHeight() { return stageHeight; }
}
