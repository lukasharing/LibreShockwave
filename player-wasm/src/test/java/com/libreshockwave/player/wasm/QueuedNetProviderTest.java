package com.libreshockwave.player.wasm;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void extensionlessHttpEndpointsKeepDistinctQueryCacheKeys() {
        QueuedNetProvider provider =
                new QueuedNetProvider("http://127.0.0.1:4174/origins300/habbo.dcr");

        String variablesUrl = "http://127.0.0.1:4174/origins-gamedata/external_variables/1?hotel=es&connectionMode=real&0";
        int variablesTask = provider.preloadNetThing(variablesUrl);
        assertEquals(1, provider.getPendingRequests().size());
        assertEquals(variablesUrl, provider.getPendingRequests().get(0).url);
        assertEquals(1, provider.getPendingRequests().get(0).fallbacks.length);
        provider.drainPendingRequests();
        provider.onFetchComplete(variablesTask, new byte[] {1, 2, 3});

        String textsUrl = "http://127.0.0.1:4174/origins-gamedata/external_texts/1?hotel=es&connectionMode=real&12361";
        int textsTask = provider.preloadNetThing(textsUrl);
        assertEquals(1, provider.getPendingRequests().size());
        assertEquals(textsUrl, provider.getPendingRequests().get(0).url);
        assertEquals("Loading", provider.getStreamStatus(textsTask));
        provider.drainPendingRequests();
        provider.onFetchComplete(textsTask, new byte[] {4, 5, 6, 7});

        int textsExactRepeatTask = provider.preloadNetThing(textsUrl);
        assertTrue(provider.netDone(textsExactRepeatTask));
        assertEquals(0, provider.getPendingRequests().size());
        assertEquals(4, provider.netTextResult(textsExactRepeatTask).length());

        int textsOtherHotelTask = provider.preloadNetThing("http://127.0.0.1:4174/origins-gamedata/external_texts/1?hotel=com&connectionMode=real&12361");
        assertEquals("Loading", provider.getStreamStatus(textsOtherHotelTask));
        assertEquals(1, provider.getPendingRequests().size());
        provider.drainPendingRequests();

        int textsOtherVersionTask = provider.preloadNetThing("http://127.0.0.1:4174/origins-gamedata/external_texts/1?hotel=es&connectionMode=real&99999");
        assertEquals("Loading", provider.getStreamStatus(textsOtherVersionTask));
        assertEquals(1, provider.getPendingRequests().size());
    }

    @Test
    void streamStatusDoesNotInventProgressBeforeFetchCompletes() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");

        int task = provider.preloadNetThing("external_texts.txt");

        assertEquals("Loading", provider.getStreamStatus(task));
        Datum.PropList loadingStatus = (Datum.PropList) provider.getStreamStatusDatum(task);
        assertEquals(0, loadingStatus.get("bytesSoFar", true).toInt());
        assertEquals(0, loadingStatus.get("bytesTotal", true).toInt());

        provider.onFetchComplete(task, new byte[] {1, 2, 3});

        assertEquals("Complete", provider.getStreamStatus(task));
        Datum.PropList completeStatus = (Datum.PropList) provider.getStreamStatusDatum(task);
        assertEquals(3, completeStatus.get("bytesSoFar", true).toInt());
        assertEquals(3, completeStatus.get("bytesTotal", true).toInt());
    }

    @Test
    void netTextResultNormalizesNetworkLineEndingsToDirectorReturn() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");

        int task = provider.preloadNetThing("external_texts.txt");
        provider.drainPendingRequests();
        provider.onFetchComplete(task, "first=value\nsecond=value\r\nthird=value"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals("first=value\rsecond=value\rthird=value", provider.netTextResult(task));
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
        assertArrayEquals(bytes, provider.netBytesResult(firstTask));

        provider.preloadNetThing("external_asset");
        assertEquals(1, provider.getPendingRequests().size());
    }

    @Test
    void rawPrefetchDoesNotCreateDirectorVisibleNetTask() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");
        List<String> callbackUrls = new ArrayList<>();
        provider.setFetchCompleteCallback((url, data) -> callbackUrls.add(url));

        int visibleTask = provider.preloadNetThing("external_texts.txt");
        assertFalse(provider.netDone(0));

        assertTrue(provider.prefetchRawResource("room.cct"));
        assertEquals(2, provider.getPendingRequests().size());
        QueuedNetProvider.PendingRequest prefetch = provider.getPendingRequests().get(1);
        assertTrue(prefetch.internalPrefetch);
        assertTrue(prefetch.taskId < 0);

        byte[] castBytes = new byte[] {7, 8, 9};
        provider.onPrefetchComplete(prefetch.url, prefetch.url, castBytes);

        assertFalse(provider.netDone(0),
                "internal prefetch must not replace the last Director-visible net task");
        assertEquals(List.of(prefetch.url), callbackUrls);
        provider.onFetchComplete(visibleTask, "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(provider.netDone(0));
        assertEquals(List.of(prefetch.url, "https://example.invalid/client/external_texts.txt"), callbackUrls);
    }

    @Test
    void rawPrefetchSeedsExactCacheForLaterDirectorRequest() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");

        assertTrue(provider.prefetchRawResource("room.cct"));
        QueuedNetProvider.PendingRequest prefetch = provider.getPendingRequests().get(0);
        provider.drainPendingRequests();

        byte[] bytes = new byte[] {4, 5, 6};
        provider.onPrefetchComplete(prefetch.url, prefetch.url, bytes);

        int laterTask = provider.preloadNetThing("room.cct");
        assertTrue(provider.netDone(laterTask));
        assertArrayEquals(bytes, provider.netBytesResult(laterTask));
        assertEquals(0, provider.getPendingRequests().size());
        assertFalse(provider.prefetchRawResource("room.cct"));
    }

    @Test
    void destinationFileAliasPublishesCastBytesToCompletionCallback() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");
        String[] callbackUrl = new String[1];
        byte[][] callbackBytes = new byte[1][];
        provider.setFetchCompleteCallback((url, data) -> {
            callbackUrl[0] = url;
            callbackBytes[0] = data;
        });

        int task = provider.preloadNetThing("room.cct");
        provider.drainPendingRequests();
        byte[] bytes = new byte[] {4, 5, 6};
        provider.onFetchComplete(task, bytes);

        provider.aliasCachedResult(task, "/tmp/director-cache/room.cct");

        assertEquals("/tmp/director-cache/room.cct", callbackUrl[0]);
        assertArrayEquals(bytes, callbackBytes[0]);
        int aliasTask = provider.preloadNetThing("/tmp/director-cache/room.cct");
        assertTrue(provider.netDone(aliasTask));
        assertArrayEquals(bytes, provider.netBytesResult(aliasTask));
        assertEquals(0, provider.getPendingRequests().size());
    }

    @Test
    void fallbackCompletionPublishesRequestedAndCompletedUrls() {
        QueuedNetProvider provider =
                new QueuedNetProvider("https://example.invalid/client/movie.dcr");
        List<String> callbackUrls = new ArrayList<>();
        provider.setFetchCompleteCallback((url, data) -> callbackUrls.add(url));

        String requestedUrl = "https://example.invalid/root/room.cct";
        String completedUrl = "https://example.invalid/client/room.cct";
        int task = provider.preloadNetThing(requestedUrl);
        provider.drainPendingRequests();

        byte[] bytes = new byte[] {9, 8, 7};
        provider.onFetchComplete(task, completedUrl, bytes);

        assertTrue(provider.netDone(task));
        assertArrayEquals(bytes, provider.netBytesResult(task));
        assertEquals(completedUrl, provider.getTaskUrl(task));
        assertEquals(List.of(requestedUrl, completedUrl), callbackUrls);
    }
}
