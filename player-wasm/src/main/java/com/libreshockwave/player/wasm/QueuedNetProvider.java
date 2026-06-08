package com.libreshockwave.player.wasm;

import com.libreshockwave.util.FileUtil;
import com.libreshockwave.player.net.RawResourcePrefetcher;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.builtin.net.NetBuiltins;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Polling-based network provider for WASM.
 * No @Import annotations — all communication is JS → WASM via @Export methods.
 *
 * When Lingo calls preloadNetThing(url), the request is queued. After each tick(),
 * JS polls for pending requests via WasmEntry, does fetch(), and delivers results
 * back via deliverFetchResult/deliverFetchError exports.
 */
public class QueuedNetProvider implements NetBuiltins.NetProvider, RawResourcePrefetcher {

    private final String basePath;
    private final Map<Integer, NetTask> tasks = new HashMap<>();
    private final Map<String, byte[]> urlCache = new HashMap<>();
    private final Map<Integer, Integer> netDonePollCounts = new HashMap<>();
    private final Set<Integer> loggedDoneTasks = new LinkedHashSet<>();
    private final List<PendingRequest> pendingRequests = new ArrayList<>();
    private final Set<String> rawPrefetchKeys = new LinkedHashSet<>();
    private int nextTaskId = 1;
    private int lastTaskId = 0;
    private int nextRawPrefetchId = -1;

    /** Called when a fetch completes with data, allowing Player to cache external cast data. */
    private java.util.function.BiConsumer<String, byte[]> fetchCompleteCallback;

    public QueuedNetProvider(String basePath) {
        this.basePath = basePath;
    }

    public void setFetchCompleteCallback(java.util.function.BiConsumer<String, byte[]> callback) {
        this.fetchCompleteCallback = callback;
    }

    public void seedCache(String url, byte[] data) {
        if (url == null || data == null) {
            return;
        }
        String resolvedUrl = resolveUrl(url);
        if (!isCastResourceUrl(resolvedUrl)) {
            cacheData(resolvedUrl, data);
            cacheData(url, data);
        }
        debug("seed cache url=" + resolvedUrl + " bytes=" + data.length);
        if (fetchCompleteCallback != null) {
            fetchCompleteCallback.accept(resolvedUrl, data);
        }
    }

    @Override
    public int preloadNetThing(String url) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        String[] requestUrls = shouldUseCastFallbacks(resolvedUrl)
                ? FileUtil.getUrlsWithFallbacks(resolvedUrl)
                : new String[] { resolvedUrl };
        String[] fallbacks = withMovieDirectoryCastFallbacks(resolvedUrl, requestUrls);

        NetTask task = new NetTask(taskId, resolvedUrl);
        task.fallbackUrls = fallbacks;
        tasks.put(taskId, task);

        byte[] cached = findCachedData(url, resolvedUrl);
        if (cached != null) {
            debug("preload cache-hit task=" + taskId + " url=" + resolvedUrl
                    + " bytes=" + cached.length);
            completeTaskFromCache(task, cached);
            notifyFetchComplete(task, cached);
            return taskId;
        }

        debug("preload queued task=" + taskId + " url=" + fallbacks[0]
                + " fallbacks=" + fallbacks.length);
        pendingRequests.add(new PendingRequest(taskId, fallbacks[0], "GET", null, fallbacks));
        return taskId;
    }

    @Override
    public int postNetText(String url, String postData) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        NetTask task = new NetTask(taskId, resolvedUrl);
        tasks.put(taskId, task);

        pendingRequests.add(new PendingRequest(taskId, resolvedUrl, "POST", postData, null));
        return taskId;
    }

    @Override
    public boolean prefetchRawResource(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String resolvedUrl = resolveUrl(url);
        String key = normalizedUrlCacheKey(resolvedUrl);
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (findExactCachedData(url, resolvedUrl) != null || rawPrefetchKeys.contains(key)) {
            return false;
        }

        String[] requestUrls = shouldUseCastFallbacks(resolvedUrl)
                ? FileUtil.getUrlsWithFallbacks(resolvedUrl)
                : new String[] { resolvedUrl };
        String[] fallbacks = withMovieDirectoryCastFallbacks(resolvedUrl, requestUrls);
        rawPrefetchKeys.add(key);
        int requestId = nextRawPrefetchId--;
        debug("prefetch queued id=" + requestId + " url=" + fallbacks[0]
                + " fallbacks=" + fallbacks.length);
        pendingRequests.add(new PendingRequest(requestId, fallbacks[0], "GET", null, fallbacks, true));
        return true;
    }

    @Override
    public boolean netDone(Integer taskId) {
        NetTask task = getTask(taskId);
        boolean done = task != null && task.done;
        if (DebugConfig.isDebugPlaybackEnabled()) {
            int effectiveTaskId = taskId == null || taskId == 0 ? lastTaskId : taskId;
            if (!done) {
                Integer previousCount = netDonePollCounts.get(effectiveTaskId);
                int count = previousCount == null ? 1 : previousCount + 1;
                netDonePollCounts.put(effectiveTaskId, count);
                if (count <= 5 || count % 60 == 0) {
                    debug("netDone pending task=" + effectiveTaskId
                            + " url=" + (task != null ? task.effectiveUrl() : "<missing>")
                            + " polls=" + count);
                }
            } else if (loggedDoneTasks.add(effectiveTaskId)) {
                debug("netDone complete task=" + effectiveTaskId
                        + " url=" + task.effectiveUrl()
                        + " bytes=" + task.byteCount
                        + " error=" + task.errorCode);
            }
        }
        return done;
    }

    @Override
    public String netTextResult(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task != null && task.done && task.data != null
                && !isCastResourceUrl(task.effectiveUrl())) {
            return normalizeDirectorText(new String(task.data, StandardCharsets.UTF_8));
        }
        return "";
    }

    @Override
    public byte[] netBytesResult(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null && task.done ? task.data : null;
    }

    @Override
    public void aliasCachedResult(Integer taskId, String alias) {
        NetTask task = getTask(taskId);
        if (task == null || !task.done || task.data == null
                || alias == null || alias.isEmpty()) {
            return;
        }
        cacheData(alias, task.data);
        if (fetchCompleteCallback != null) {
            fetchCompleteCallback.accept(alias, task.data);
        }
    }

    @Override
    public int netError(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null ? task.errorCode : 0;
    }

    @Override
    public String getStreamStatus(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task == null) return "Error";
        if (task.done) return task.errorCode == 0 ? "Complete" : "Error";
        return "Loading";
    }

    /**
     * Returns stream status as a PropList matching Director's observable state.
     *
     * Browser fetch does not expose partial bytes to this provider, so report
     * zero until the completed result is delivered. Inventing progress here can
     * let authored polling code advance before the downloaded bytes are usable.
     */
    @Override
    public Datum getStreamStatusDatum(Integer taskId) {
        NetTask task = getTask(taskId);
        java.util.LinkedHashMap<String, Datum> props = new java.util.LinkedHashMap<>();
        if (task == null) {
            props.put("URL",        Datum.EMPTY_STRING);
            props.put("state",      Datum.of("Error"));
            props.put("bytesSoFar", Datum.ZERO);
            props.put("bytesTotal", Datum.ZERO);
            props.put("error",      Datum.of("OK"));
            return Datum.propList(props);
        }
        int byteCount = task.done ? task.byteCount : 0;
        String state  = task.done ? (task.errorCode == 0 ? "Complete" : "Error") : "Loading";
        props.put("URL",        Datum.EMPTY_STRING);
        props.put("state",      Datum.of(state));
        props.put("bytesSoFar", Datum.of(byteCount));
        props.put("bytesTotal", Datum.of(task.done ? task.byteCount : 0));
        props.put("error",      task.errorCode == 0 ? Datum.of("OK") : Datum.of(String.valueOf(task.errorCode)));
        return Datum.propList(props);
    }

    /**
     * Get the URL for a task (used to identify external cast loads).
     */
    public String getTaskUrl(int taskId) {
        NetTask task = tasks.get(taskId);
        return task != null ? task.effectiveUrl() : null;
    }

    /**
     * Get all pending requests for JS to read.
     */
    public List<PendingRequest> getPendingRequests() {
        return pendingRequests;
    }

    /**
     * Get a pending request by index for indexed WASM export access.
     */
    public PendingRequest getRequest(int index) {
        return (index >= 0 && index < pendingRequests.size())
                ? pendingRequests.get(index) : null;
    }

    /**
     * Clear pending requests after JS has read them.
     */
    public void drainPendingRequests() {
        pendingRequests.clear();
    }

    /**
     * Called when JS delivers a successful fetch result.
     */
    public void onFetchComplete(int taskId, byte[] data) {
        onFetchComplete(taskId, null, data);
    }

    /**
     * Called when JS delivers a successful fetch result.
     *
     * The completed URL may differ from the requested URL when the browser side
     * used a fallback. Director code observes completion for the original
     * preloadNetThing URL, so publish both identities to the raw cache layer.
     */
    public void onFetchComplete(int taskId, String completedUrl, byte[] data) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            String resolvedCompletedUrl = completedUrl == null || completedUrl.isEmpty()
                    ? null
                    : resolveUrl(completedUrl);
            if (resolvedCompletedUrl != null && !resolvedCompletedUrl.isEmpty()) {
                task.completedUrl = resolvedCompletedUrl;
            }
            debug("fetch complete task=" + taskId
                    + " requested=" + task.requestedUrl
                    + " completed=" + task.effectiveUrl()
                    + " bytes=" + (data != null ? data.length : 0));
            task.byteCount = data != null ? data.length : 0;
            task.done = true;

            boolean retainData = !isCastResourceUrl(task.effectiveUrl());
            task.data = data;
            if (retainData && data != null) {
                cacheData(task.requestedUrl, data);
                if (!Objects.equals(task.requestedUrl, task.effectiveUrl())) {
                    cacheData(task.effectiveUrl(), data);
                }
            }

            notifyFetchComplete(task, data);
        }
    }

    public void onPrefetchComplete(String requestedUrl, String completedUrl, byte[] data) {
        if (data == null) {
            return;
        }
        String resolvedRequestedUrl = requestedUrl == null || requestedUrl.isEmpty()
                ? null
                : resolveUrl(requestedUrl);
        String resolvedCompletedUrl = completedUrl == null || completedUrl.isEmpty()
                ? null
                : resolveUrl(completedUrl);
        if (resolvedRequestedUrl != null && !resolvedRequestedUrl.isEmpty()) {
            cacheExactData(resolvedRequestedUrl, data);
            String key = normalizedUrlCacheKey(resolvedRequestedUrl);
            if (key != null) {
                rawPrefetchKeys.add(key);
            }
        }
        if (resolvedCompletedUrl != null && !resolvedCompletedUrl.isEmpty()
                && !Objects.equals(resolvedRequestedUrl, resolvedCompletedUrl)) {
            cacheExactData(resolvedCompletedUrl, data);
            String key = normalizedUrlCacheKey(resolvedCompletedUrl);
            if (key != null) {
                rawPrefetchKeys.add(key);
            }
        }
        debug("prefetch complete requested=" + resolvedRequestedUrl
                + " completed=" + resolvedCompletedUrl
                + " bytes=" + data.length);
        notifyPrefetchComplete(resolvedRequestedUrl, resolvedCompletedUrl, data);
    }

    /**
     * Mark a fetch task as done with only the byte count (no data stored).
     * Used for cast files where bytes stay in JS memory.
     * bytesSoFar/bytesTotal report correctly for Lingo's download check.
     */
    public void onFetchStatusComplete(int taskId, int byteCount) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            debug("fetch status-complete task=" + taskId + " url=" + task.effectiveUrl()
                    + " bytes=" + byteCount);
            task.data = null;
            task.byteCount = byteCount;
            task.done = true;
        }
    }

    /**
     * Called when JS delivers a fetch error.
     */
    public void onFetchError(int taskId, int status) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            debug("fetch error task=" + taskId + " url=" + task.effectiveUrl()
                    + " status=" + status);
            task.errorCode = status != 0 ? status : -1;
            task.done = true;
        }
    }

    private static void debug(String message) {
        if (DebugConfig.isDebugPlaybackEnabled()) {
            System.out.println("[QueuedNet] " + message);
        }
    }

    private NetTask getTask(Integer taskId) {
        if (taskId == null || taskId == 0) {
            return lastTaskId > 0 ? tasks.get(lastTaskId) : null;
        }
        return tasks.get(taskId);
    }

    private String resolveUrl(String url) {
        if (url == null || url.isEmpty()) return url;

        // If already absolute, use as-is
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        // Root-relative URL (e.g. "/gamedata/external_variables.txt") —
        // resolve against the server origin, not the movie's directory.
        if (url.startsWith("/") && basePath != null) {
            String origin = extractOrigin(basePath);
            if (origin != null) {
                return origin + url;
            }
        }

        // Extract just the filename (strip any path from the author's machine)
        // Handles /, \, and : (Mac-style Director paths like "Sulake:...:file.cct")
        String fileName = FileUtil.getFileName(url);

        // Resolve against basePath
        if (basePath != null && !basePath.isEmpty()) {
            String base = basePath;
            // Remove trailing filename from basePath if it looks like a file
            int baseSlash = base.lastIndexOf('/');
            if (baseSlash >= 0 && base.lastIndexOf('.') > baseSlash) {
                base = base.substring(0, baseSlash + 1);
            } else if (!base.endsWith("/")) {
                base = base + "/";
            }
            return base + fileName;
        }

        return fileName;
    }

    private String[] withMovieDirectoryCastFallbacks(String resolvedUrl, String[] fallbacks) {
        if (resolvedUrl == null || basePath == null || basePath.isEmpty()) {
            return fallbacks;
        }
        if (!shouldUseCastFallbacks(resolvedUrl)) {
            return fallbacks;
        }

        String fileName = FileUtil.getFileName(resolvedUrl);
        if (fileName == null || fileName.isEmpty()) {
            return fallbacks;
        }

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!(lowerName.endsWith(".cct") || lowerName.endsWith(".cst") || !lowerName.contains("."))) {
            return fallbacks;
        }

        String origin = extractOrigin(basePath);
        String movieDir = extractMovieDirectory(basePath);
        if (origin == null || movieDir == null || movieDir.equals(origin + "/")) {
            return fallbacks;
        }

        String rootUrl = origin + "/" + fileName;
        String lowerResolved = resolvedUrl.toLowerCase(Locale.ROOT);
        if (!lowerResolved.equals(rootUrl.toLowerCase(Locale.ROOT))
                && !lowerResolved.equals((rootUrl + ".cct").toLowerCase(Locale.ROOT))
                && !lowerResolved.equals((rootUrl + ".cst").toLowerCase(Locale.ROOT))) {
            return fallbacks;
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        String movieDirUrl = movieDir + fileName;
        for (String fallback : FileUtil.getUrlsWithFallbacks(movieDirUrl)) {
            urls.add(fallback);
        }
        if (fallbacks != null) {
            for (String fallback : fallbacks) {
                if (fallback != null && !fallback.isEmpty()) {
                    urls.add(fallback);
                }
            }
        }
        return urls.toArray(new String[0]);
    }

    private byte[] findCachedData(String originalUrl, String resolvedUrl) {
        for (String key : buildCacheKeys(originalUrl, resolvedUrl)) {
            byte[] cached = urlCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    private void completeTaskFromCache(NetTask task, byte[] data) {
        task.data = data;
        task.byteCount = data.length;
        task.done = true;
    }

    private void notifyFetchComplete(NetTask task, byte[] data) {
        if (fetchCompleteCallback == null || task == null || data == null) {
            return;
        }
        if (task.requestedUrl != null) {
            fetchCompleteCallback.accept(task.requestedUrl, data);
        }
        String completedUrl = task.effectiveUrl();
        if (completedUrl != null && !Objects.equals(task.requestedUrl, completedUrl)) {
            fetchCompleteCallback.accept(completedUrl, data);
        }
    }

    private void notifyPrefetchComplete(String requestedUrl, String completedUrl, byte[] data) {
        if (fetchCompleteCallback == null || data == null) {
            return;
        }
        if (requestedUrl != null && !requestedUrl.isEmpty()) {
            fetchCompleteCallback.accept(requestedUrl, data);
        }
        if (completedUrl != null && !completedUrl.isEmpty()
                && !Objects.equals(requestedUrl, completedUrl)) {
            fetchCompleteCallback.accept(completedUrl, data);
        }
    }

    private void cacheData(String url, byte[] data) {
        if (url == null || url.isEmpty() || data == null) {
            return;
        }
        for (String key : buildCacheKeys(url, url)) {
            urlCache.put(key, data);
        }
    }

    private void cacheExactData(String url, byte[] data) {
        if (url == null || url.isEmpty() || data == null) {
            return;
        }
        String key = normalizedUrlCacheKey(url);
        if (key != null && !key.isEmpty()) {
            urlCache.put(key, data);
        }
    }

    private byte[] findExactCachedData(String originalUrl, String resolvedUrl) {
        String originalKey = normalizedUrlCacheKey(originalUrl);
        if (originalKey != null) {
            byte[] data = urlCache.get(originalKey);
            if (data != null) {
                return data;
            }
        }
        String resolvedKey = normalizedUrlCacheKey(resolvedUrl);
        return resolvedKey != null ? urlCache.get(resolvedKey) : null;
    }

    private Set<String> buildCacheKeys(String originalUrl, String resolvedUrl) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addCacheKeys(keys, originalUrl);
        addCacheKeys(keys, resolvedUrl);
        return keys;
    }

    private void addCacheKeys(Set<String> keys, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        String normalizedUrl = normalizedUrlCacheKey(url);
        if (normalizedUrl != null && !normalizedUrl.isEmpty()) {
            keys.add(normalizedUrl);
        }
        if (hasQuery(url)) {
            return;
        }
        String fileName = FileUtil.getFileName(url);
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        if (isAmbiguousCacheFileName(fileName)) {
            return;
        }
        keys.add(fileName.toLowerCase(Locale.ROOT));
        String baseName = FileUtil.getFileNameWithoutExtension(fileName);
        if (baseName != null && !baseName.isEmpty()) {
            keys.add(baseName.toLowerCase(Locale.ROOT));
        }
    }

    private static boolean shouldUseCastFallbacks(String url) {
        return !isExtensionlessHttpResource(url);
    }

    private static boolean isExtensionlessHttpResource(String url) {
        if (!isAbsoluteHttpUrl(url)) {
            return false;
        }
        String fileName = FileUtil.getFileName(url);
        return fileName != null && !fileName.isEmpty() && !fileName.contains(".");
    }

    private static boolean isAmbiguousCacheFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return true;
        }
        String clean = fileName;
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        return clean.matches("\\d+");
    }

    private static String normalizedUrlCacheKey(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        if (isAbsoluteHttpUrl(url)) {
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                String path = uri.getPath();
                if (scheme == null || host == null || path == null || path.isEmpty()) {
                    return null;
                }
                int port = uri.getPort();
                String authority = port >= 0 ? host + ":" + port : host;
                String query = uri.getRawQuery();
                String key = scheme + "://" + authority + path;
                if (query != null && !query.isEmpty()) {
                    key = key + "?" + query;
                }
                return key.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }
        return url.toLowerCase(Locale.ROOT);
    }

    private static String normalizeDirectorText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\r\n", "\r").replace('\n', '\r');
    }

    private static boolean hasQuery(String url) {
        return url != null && url.indexOf('?') >= 0;
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static boolean isCastResourceUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".cct") || lower.endsWith(".cst");
    }

    /** Extract the origin (scheme + host + port) from an absolute URL. */
    private static String extractOrigin(String url) {
        if (url == null) return null;
        // Find the third slash: https://host[:port]/...
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart >= 0 ? url.substring(0, pathStart) : url;
    }

    private static String extractMovieDirectory(String url) {
        if (url == null || url.isEmpty()) return null;
        int query = url.indexOf('?');
        String clean = query >= 0 ? url.substring(0, query) : url;
        int slash = clean.lastIndexOf('/');
        if (slash < 0) return null;
        return clean.substring(0, slash + 1);
    }

    /**
     * A pending network request for JS to execute.
     * Uses a plain class instead of a record for TeaVM compatibility
     * (TeaVM may not correctly handle String[] fields in records).
     */
    public static class PendingRequest {
        public final int taskId;
        public final String url;
        public final String method;
        public final String postData;
        public final String[] fallbacks;
        public final boolean internalPrefetch;

        public PendingRequest(int taskId, String url, String method, String postData, String[] fallbacks) {
            this(taskId, url, method, postData, fallbacks, false);
        }

        public PendingRequest(int taskId, String url, String method, String postData,
                              String[] fallbacks, boolean internalPrefetch) {
            this.taskId = taskId;
            this.url = url;
            this.method = method;
            this.postData = postData;
            this.fallbacks = fallbacks;
            this.internalPrefetch = internalPrefetch;
        }
    }

    // Simple task data holder
    static class NetTask {
        final int id;
        final String requestedUrl;
        String completedUrl;
        byte[] data;
        int byteCount;
        int errorCode;
        boolean done;
        String[] fallbackUrls;
        int pollCount;

        NetTask(int id, String url) {
            this.id = id;
            this.requestedUrl = url;
        }

        String effectiveUrl() {
            return completedUrl != null && !completedUrl.isEmpty() ? completedUrl : requestedUrl;
        }
    }
}
