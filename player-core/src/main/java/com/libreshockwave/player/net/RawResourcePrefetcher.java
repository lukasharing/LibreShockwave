package com.libreshockwave.player.net;

/**
 * Optional host-side network optimization for downloads that should be cached
 * without creating a Director-visible net task.
 */
public interface RawResourcePrefetcher {
    boolean prefetchRawResource(String url);
}
