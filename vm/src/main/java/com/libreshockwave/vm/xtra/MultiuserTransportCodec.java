package com.libreshockwave.vm.xtra;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes Director Multiuser messages onto the raw byte stream used by local
 * transports and decodes raw stream chunks back into subject/content pairs.
 */
public final class MultiuserTransportCodec {

    public static final int SMUS_STRING = 3;

    private static final int SMUS_HEADER_SIZE = 14;
    private static final int SMUS_SIZE_PREFIX = 6;
    private static final byte SMUS_HEADER_TAG = 0x72;

    private MultiuserTransportCodec() {
    }

    public static boolean isContentOnlyEnvelope(String senderID, String subject) {
        return "0".equals(senderID) && "0".equals(subject);
    }

    /**
     * Returns whether the supplied bytes can still be interpreted as the start
     * of an SMUS packet. This deliberately accepts incomplete prefixes because
     * TCP/WebSocket delivery may split a packet between callbacks.
     */
    public static boolean couldStartSmusPacket(String rawContent) {
        byte[] data = rawContent != null
                ? rawContent.getBytes(StandardCharsets.ISO_8859_1)
                : new byte[0];
        if (data.length == 0) {
            return true;
        }
        if (data[0] != SMUS_HEADER_TAG) {
            return false;
        }
        return data.length == 1 || data[1] == 0;
    }

    public static String encodeSmusPacket(String recipients, String subject, String content) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeSmusString(body, subject);
        writeSmusString(body, "");
        writeInt32(body, recipients == null || recipients.isEmpty() ? 0 : 1);
        if (recipients != null && !recipients.isEmpty()) {
            writeSmusString(body, recipients);
        }
        writeInt16(body, SMUS_STRING);
        writeSmusString(body, content);

        byte[] payload = body.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream(SMUS_HEADER_SIZE + payload.length);
        packet.write(SMUS_HEADER_TAG);
        packet.write(0);
        writeInt32(packet, payload.length + 8);
        writeInt32(packet, 0);
        writeInt32(packet, (int) System.currentTimeMillis());
        packet.write(payload, 0, payload.length);
        return new String(packet.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    public static SmusParseResult parseSmusPackets(String rawContent) {
        byte[] data = rawContent != null
                ? rawContent.getBytes(StandardCharsets.ISO_8859_1)
                : new byte[0];
        List<SmusMessage> messages = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            if (data.length - offset < SMUS_HEADER_SIZE) {
                break;
            }
            if (data[offset] != SMUS_HEADER_TAG || data[offset + 1] != 0) {
                break;
            }
            int packetSize = SMUS_SIZE_PREFIX + readInt32(data, offset + 2);
            if (packetSize < SMUS_HEADER_SIZE) {
                break;
            }
            if (data.length - offset < packetSize) {
                break;
            }
            SmusMessage message = parseSinglePacket(data, offset, packetSize);
            if (message == null) {
                break;
            }
            messages.add(message);
            offset += packetSize;
        }
        return new SmusParseResult(messages, offset);
    }

    public record SmusMessage(int errorCode, String senderID, String subject, String content, int contentType) {}

    public record SmusParseResult(List<SmusMessage> messages, int consumedChars) {}

    private static SmusMessage parseSinglePacket(byte[] data, int packetOffset, int packetSize) {
        int errorCode = readInt32(data, packetOffset + 6);
        Cursor cursor = new Cursor(packetOffset + SMUS_HEADER_SIZE, packetOffset + packetSize);
        String subject = readSmusString(data, cursor);
        String senderID = readSmusString(data, cursor);
        if (!cursor.has(4)) {
            return null;
        }
        int recipientCount = readInt32(data, cursor.position);
        cursor.position += 4;
        for (int i = 0; i < recipientCount; i++) {
            readSmusString(data, cursor);
        }
        if (!cursor.has(2)) {
            return null;
        }
        int contentType = readUInt16(data, cursor.position);
        cursor.position += 2;
        String content;
        if (contentType == SMUS_STRING) {
            content = readSmusString(data, cursor);
        } else if (contentType == 0) {
            content = "";
        } else {
            int remaining = Math.max(0, cursor.limit - cursor.position);
            content = new String(data, cursor.position, remaining, StandardCharsets.ISO_8859_1);
        }
        return new SmusMessage(errorCode, senderID, subject, content, contentType);
    }

    private static void writeSmusString(ByteArrayOutputStream out, String value) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.ISO_8859_1);
        writeInt32(out, bytes.length);
        out.write(bytes, 0, bytes.length);
        if ((bytes.length & 1) == 1) {
            out.write(0);
        }
    }

    private static String readSmusString(byte[] data, Cursor cursor) {
        if (!cursor.has(4)) {
            cursor.position = cursor.limit;
            return "";
        }
        int length = readInt32(data, cursor.position);
        cursor.position += 4;
        if (length < 0 || !cursor.has(length)) {
            cursor.position = cursor.limit;
            return "";
        }
        String value = new String(data, cursor.position, length, StandardCharsets.ISO_8859_1);
        cursor.position += length;
        if ((length & 1) == 1 && cursor.has(1)) {
            cursor.position++;
        }
        return value;
    }

    private static int readUInt16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8)
                | (data[offset + 1] & 0xff);
    }

    private static int readInt32(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static void writeInt16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static final class Cursor {
        int position;
        final int limit;

        Cursor(int position, int limit) {
            this.position = position;
            this.limit = limit;
        }

        boolean has(int count) {
            return count >= 0 && position + count <= limit;
        }
    }
}
