package com.libreshockwave.util;

import java.net.URI;

public class FileUtil {

    /**
     * Extract just the filename from a path or URL.
     * Handles HTTP URLs, Windows paths (D:\path\file.cst), and Unix paths.
     */
    public static String getFileName(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }

        // For HTTP URLs, use URI parsing
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                URI uri = URI.create(path);
                String uriPath = uri.getPath();
                if (uriPath != null && !uriPath.isEmpty()) {
                    int lastSlash = uriPath.lastIndexOf('/');
                    return lastSlash >= 0 ? uriPath.substring(lastSlash + 1) : uriPath;
                }
            } catch (Exception e) {
                // Fall through to path-based extraction
            }
        }

        // Strip path (handles /, \, and : as Mac-style Director path separators).
        // This runs in hot cast-loading paths, so keep it allocation-light and
        // avoid regex backtracking on long authored URLs.
        String decoded = path.replace("%20", " ");
        int lastUnixSeparator = decoded.lastIndexOf('/');
        int lastWindowsSeparator = decoded.lastIndexOf('\\');
        int lastMacSeparator = decoded.lastIndexOf(':');
        int separator = Math.max(lastUnixSeparator, Math.max(lastWindowsSeparator, lastMacSeparator));
        return separator >= 0 ? decoded.substring(separator + 1) : decoded;
    }

    /**
     * Build a list of URLs to try while preserving the authored extension first.
     * For cast files (.cst/.cct), try the requested URL first and then the paired
     * compressed/uncompressed variant.
     * For movie files (.dcr/.dxr/.dir), returns [original, .dcr, .dxr, .dir].
     * Otherwise returns just the original URL.
     */
    public static String[] getUrlsWithFallbacks(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".cst")) {
            String base = url.substring(0, url.length() - 4);
            return new String[] { url, base + ".cct" };
        }
        if (lower.endsWith(".cct")) {
            String base = url.substring(0, url.length() - 4);
            return new String[] { url, base + ".cst" };
        }
        if (lower.endsWith(".dcr") || lower.endsWith(".dxr") || lower.endsWith(".dir")) {
            String base = url.substring(0, url.length() - 4);
            return new String[] { url, base + ".dcr", base + ".dxr", base + ".dir" };
        }
        // No recognized extension (e.g. "hosts") — try .cct and .cst as cast fallbacks.
        // Check the filename portion only (URL may contain dots in hostname).
        String fileName = getFileName(url);
        if (fileName != null && !fileName.contains(".")) {
            return new String[] { url + ".cct", url + ".cst" };
        }
        return new String[] { url };
    }

    public static String getFileNameWithoutExtension(String string) {
        if (string == null || string.isEmpty()) {
            return string;
        }

        // Get just the filename first
        String fileName = getFileName(string);

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) { // no extension or hidden file like ".gitignore"
            return fileName;
        }

        return fileName.substring(0, lastDot);
    }
}
