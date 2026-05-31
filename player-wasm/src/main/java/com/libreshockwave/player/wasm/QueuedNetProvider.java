package com.libreshockwave.player.wasm;

import com.libreshockwave.util.FileUtil;
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
import java.util.Set;

/**
 * Polling-based network provider for WASM.
 * No @Import annotations — all communication is JS → WASM via @Export methods.
 *
 * When Lingo calls preloadNetThing(url), the request is queued. After each tick(),
 * JS polls for pending requests via WasmEntry, does fetch(), and delivers results
 * back via deliverFetchResult/deliverFetchError exports.
 */
public class QueuedNetProvider implements NetBuiltins.NetProvider {

    private final String basePath;
    private final Map<Integer, NetTask> tasks = new HashMap<>();
    private final Map<String, byte[]> urlCache = new HashMap<>();
    private final Map<Integer, Integer> netDonePollCounts = new HashMap<>();
    private final Set<Integer> loggedDoneTasks = new LinkedHashSet<>();
    private final List<PendingRequest> pendingRequests = new ArrayList<>();
    private int nextTaskId = 1;
    private int lastTaskId = 0;

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
            if (fetchCompleteCallback != null) {
                fetchCompleteCallback.accept(task.url, cached);
            }
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
                            + " url=" + (task != null ? task.url : "<missing>")
                            + " polls=" + count);
                }
            } else if (loggedDoneTasks.add(effectiveTaskId)) {
                debug("netDone complete task=" + effectiveTaskId
                        + " url=" + task.url
                        + " bytes=" + task.byteCount
                        + " error=" + task.errorCode);
            }
        }
        return done;
    }

    @Override
    public String netTextResult(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task != null && task.done && task.data != null) {
            return new String(task.data, StandardCharsets.UTF_8);
        }
        return "";
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
     * Returns stream status as a PropList with real bytesSoFar so that the
     * Download Instance's check (tStreamStatus[#bytesSoFar] > 0) passes.
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
        int byteCount;
        if (task.done) {
            byteCount = task.byteCount;
        } else {
            // Report incrementing bytesSoFar while loading so authored polling
            // code can observe progress instead of treating the request as stalled.
            // JS fetch() doesn't provide intermediate progress, but the
            // Director plugin would report bytes as they stream in.
            task.pollCount++;
            byteCount = task.pollCount;
        }
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
        return task != null ? task.url : null;
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
        NetTask task = tasks.get(taskId);
        if (task != null) {
            debug("fetch complete task=" + taskId + " url=" + task.url
                    + " bytes=" + (data != null ? data.length : 0));
            task.byteCount = data != null ? data.length : 0;
            task.done = true;

            boolean retainData = !isCastResourceUrl(task.url);
            task.data = retainData ? data : null;
            if (retainData && task.url != null && data != null) {
                cacheData(task.url, data);
            }

            if (fetchCompleteCallback != null && task.url != null && data != null) {
                fetchCompleteCallback.accept(task.url, data);
            }
        }
    }

    /**
     * Mark a fetch task as done with only the byte count (no data stored).
     * Used for cast files where bytes stay in JS memory.
     * bytesSoFar/bytesTotal report correctly for Lingo's download check.
     */
    public void onFetchStatusComplete(int taskId, int byteCount) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            debug("fetch status-complete task=" + taskId + " url=" + task.url
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
            debug("fetch error task=" + taskId + " url=" + task.url
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

    private void cacheData(String url, byte[] data) {
        if (url == null || url.isEmpty() || data == null) {
            return;
        }
        for (String key : buildCacheKeys(url, url)) {
            urlCache.put(key, data);
        }
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
                return (scheme + "://" + authority + path).toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }
        int query = url.indexOf('?');
        String clean = query >= 0 ? url.substring(0, query) : url;
        return clean.toLowerCase(Locale.ROOT);
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

        public PendingRequest(int taskId, String url, String method, String postData, String[] fallbacks) {
            this.taskId = taskId;
            this.url = url;
            this.method = method;
            this.postData = postData;
            this.fallbacks = fallbacks;
        }
    }

    // Simple task data holder
    static class NetTask {
        final int id;
        final String url;
        byte[] data;
        int byteCount;
        int errorCode;
        boolean done;
        String[] fallbackUrls;
        int pollCount;

        NetTask(int id, String url) {
            this.id = id;
            this.url = url;
        }
    }
}
