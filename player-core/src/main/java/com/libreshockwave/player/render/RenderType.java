package com.libreshockwave.player.render;

/**
 * Rendering backend for frame compositing.
 */
public enum RenderType {
    /** Pure int[] software compositing (WASM-safe, no AWT dependency). */
    SOFTWARE
}
