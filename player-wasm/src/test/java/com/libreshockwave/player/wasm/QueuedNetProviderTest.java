package com.libreshockwave.player.wasm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuedNetProviderTest {

    @Test
    void textDataCacheMatchesBasenameAfterExtensionFetch() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");

        int firstTask = provider.preloadNetThing("external_texts.txt");
        assertEquals(1, provider.getPendingRequests().size());
        provider.drainPendingRequests();

        byte[] bytes = new byte[] {1, 2, 3};
        provider.onFetchComplete(firstTask, bytes);

        int secondTask = provider.preloadNetThing("external_texts");
        assertTrue(provider.netDone(secondTask));
        assertEquals(0, provider.getPendingRequests().size());
        assertEquals("Complete", provider.getStreamStatus(secondTask));
    }

    @Test
    void extensionlessHttpEndpointsUseUrlCacheKeys() {
        QueuedNetProvider provider =
                new QueuedNetProvider("http://127.0.0.1:4174/origins300/habbo.dcr");

        int variablesTask = provider.preloadNetThing("http://127.0.0.1:4174/origins-gamedata/external_variables/1?0");
        assertEquals(1, provider.getPendingRequests().size());
        assertEquals("http://127.0.0.1:4174/origins-gamedata/external_variables/1?0",
                provider.getPendingRequests().get(0).url);
        assertEquals(1, provider.getPendingRequests().get(0).fallbacks.length);
        provider.drainPendingRequests();
        provider.onFetchComplete(variablesTask, new byte[] {1, 2, 3});

        int textsTask = provider.preloadNetThing("http://127.0.0.1:4174/origins-gamedata/external_texts/1?12361");
        assertEquals(1, provider.getPendingRequests().size());
        assertEquals("http://127.0.0.1:4174/origins-gamedata/external_texts/1?12361",
                provider.getPendingRequests().get(0).url);
        assertEquals("Loading", provider.getStreamStatus(textsTask));
        provider.drainPendingRequests();
        provider.onFetchComplete(textsTask, new byte[] {4, 5, 6, 7});

        int textsCacheBustTask = provider.preloadNetThing("http://127.0.0.1:4174/origins-gamedata/external_texts/1?99999");
        assertTrue(provider.netDone(textsCacheBustTask));
        assertEquals(0, provider.getPendingRequests().size());
        assertEquals(4, provider.netTextResult(textsCacheBustTask).length());
    }

    @Test
    void castFetchesAreNotRetainedInGenericNetCache() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");

        int firstTask = provider.preloadNetThing("external_asset.cct");
        assertEquals(1, provider.getPendingRequests().size());
        provider.drainPendingRequests();

        byte[] bytes = new byte[] {1, 2, 3};
        provider.onFetchComplete(firstTask, bytes);

        assertTrue(provider.netDone(firstTask));
        assertEquals("Complete", provider.getStreamStatus(firstTask));
        assertEquals("", provider.netTextResult(firstTask));

        provider.preloadNetThing("external_asset");
        assertEquals(1, provider.getPendingRequests().size());
    }
}
