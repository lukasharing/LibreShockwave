package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

final class WasmJpegDecoder {
    private static final Map<Integer, byte[]> pending = new LinkedHashMap<>();
    private static final Map<Integer, Bitmap> decoded = new LinkedHashMap<>();
    private static int[] pendingIds = new int[16];
    private static int pendingIdCount;
    private static byte[] currentData;

    private WasmJpegDecoder() {
    }

    static Bitmap decode(byte[] jpegData) {
        int id = idFor(jpegData);
        Bitmap bitmap = decoded.get(id);
        if (bitmap != null) {
            return bitmap;
        }
        if (!pending.containsKey(id)) {
            pending.put(id, Arrays.copyOf(jpegData, jpegData.length));
            appendPendingId(id);
        }
        DirectorFile.markJpegDecodePending();
        return null;
    }

    static int pendingCount() {
        return pendingIdCount;
    }

    static int pendingId(int index) {
        return index >= 0 && index < pendingIdCount ? pendingIds[index] : 0;
    }

    static int prepareData(int id) {
        currentData = pending.get(id);
        return currentData != null ? currentData.length : 0;
    }

    static byte[] currentData() {
        return currentData;
    }

    static void reset() {
        pending.clear();
        decoded.clear();
        pendingIdCount = 0;
        currentData = null;
    }

    static void releaseTransientData() {
        currentData = null;
    }

    static void resetForTest() {
        reset();
    }

    static void deliverDecoded(int id, int width, int height, byte[] rgba) {
        if (width <= 0 || height <= 0 || rgba == null || rgba.length < width * height * 4) {
            pending.remove(id);
            removePendingId(id);
            return;
        }
        Bitmap bitmap = new Bitmap(width, height, 32);
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rgba[p++] & 0xFF;
                int g = rgba[p++] & 0xFF;
                int b = rgba[p++] & 0xFF;
                int a = rgba[p++] & 0xFF;
                bitmap.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        bitmap.setNativeAlpha(true);
        decoded.put(id, bitmap);
        pending.remove(id);
        removePendingId(id);
        currentData = null;
    }

    private static void appendPendingId(int id) {
        if (pendingIdCount >= pendingIds.length) {
            int[] next = new int[pendingIds.length * 2];
            System.arraycopy(pendingIds, 0, next, 0, pendingIds.length);
            pendingIds = next;
        }
        pendingIds[pendingIdCount++] = id;
    }

    private static void removePendingId(int id) {
        for (int i = 0; i < pendingIdCount; i++) {
            if (pendingIds[i] != id) {
                continue;
            }
            int move = pendingIdCount - i - 1;
            if (move > 0) {
                System.arraycopy(pendingIds, i + 1, pendingIds, i, move);
            }
            pendingIds[--pendingIdCount] = 0;
            return;
        }
    }

    private static int idFor(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }
}
