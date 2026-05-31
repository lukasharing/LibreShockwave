package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiuserTransportStateTest {

    @Test
    void contentOnlyModePassesIncomingAndOutgoingPayloadsThrough() {
        MultiuserTransportState state = new MultiuserTransportState(1);

        assertEquals("@@command", state.encodeOutgoing("*", "GET", Datum.of("@@command")));

        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", "@@reply");
        assertEquals(1, decoded.size());
        assertEquals("@@reply", decoded.get(0).content().toStr());
    }

    @Test
    void smusModeParsesCompletePackets() {
        MultiuserTransportState state = new MultiuserTransportState(0);
        String packet = MultiuserTransportCodec.encodeSmusPacket("!", "HELLO", "session");

        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", packet);

        assertEquals(1, decoded.size());
        assertEquals("HELLO", decoded.get(0).subject());
        assertEquals("session", decoded.get(0).content().toStr());
    }

    @Test
    void smusModePreservesNonStringPayloadsForBinaryCallbacks() {
        MultiuserTransportState state = new MultiuserTransportState(0);
        String packet = encodeSmusPacketWithRawContent("System", "BINDATA", 5, "\u0000abc\u00ff");

        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", packet);

        assertEquals(1, decoded.size());
        assertEquals("BINDATA", decoded.get(0).subject());
        Datum content = decoded.get(0).content();
        assertTrue(content instanceof Datum.BinaryData);
        assertFalse(content.isString());
        assertEquals("binary", content.typeName());
        assertEquals("\u0000abc\u00ff", content.toStr());
    }

    @Test
    void smusModeKeepsZeroContentTypeAsEmptyString() {
        MultiuserTransportState state = new MultiuserTransportState(0);
        String packet = encodeSmusPacketWithRawContent("System", "EMPTY", 0, "");

        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", packet);

        assertEquals(1, decoded.size());
        assertTrue(decoded.get(0).content().isString());
        assertEquals("", decoded.get(0).content().toStr());
    }

    @Test
    void smusModeBuffersSplitPrefixes() {
        MultiuserTransportState state = new MultiuserTransportState(0);
        String packet = MultiuserTransportCodec.encodeSmusPacket("!", "HELLO", "session");

        assertEquals(List.of(), state.decodeIncoming(0, "", "", packet.substring(0, 1)));
        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", packet.substring(1));

        assertEquals(1, decoded.size());
        assertEquals("HELLO", decoded.get(0).subject());
    }

    @Test
    void smusModeFallsBackToRawWhenStreamCannotBeSmus() {
        MultiuserTransportState state = new MultiuserTransportState(0);

        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", "@@raw-fuse-payload");

        assertEquals(1, decoded.size());
        assertEquals("@@raw-fuse-payload", decoded.get(0).content().toStr());
        assertEquals("@@next-command", state.encodeOutgoing("*", "GET_PAGE_ARTICLES", Datum.of("@@next-command")));
    }

    @Test
    void smusModeDeliversRawRemainderAfterCompletePacket() {
        MultiuserTransportState state = new MultiuserTransportState(0);
        String packet = MultiuserTransportCodec.encodeSmusPacket("!", "HELLO", "session");

        List<MultiuserNetBridge.NetMessage> decoded =
                state.decodeIncoming(0, "", "", packet + "@@raw-fuse-payload");

        assertEquals(2, decoded.size());
        assertEquals("HELLO", decoded.get(0).subject());
        assertEquals("@@raw-fuse-payload", decoded.get(1).content().toStr());
        assertEquals("@@next-command", state.encodeOutgoing("*", "GET_PAGE_ARTICLES", Datum.of("@@next-command")));
    }

    private static String encodeSmusPacketWithRawContent(
            String senderID, String subject, int contentType, String rawContent) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeSmusString(body, subject);
        writeSmusString(body, senderID);
        writeInt32(body, 1);
        writeSmusString(body, "!");
        writeInt16(body, contentType);
        body.writeBytes(rawContent.getBytes(StandardCharsets.ISO_8859_1));

        byte[] payload = body.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream(14 + payload.length);
        packet.write(0x72);
        packet.write(0);
        writeInt32(packet, payload.length + 8);
        writeInt32(packet, 0);
        writeInt32(packet, 0);
        packet.writeBytes(payload);
        return new String(packet.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void writeSmusString(ByteArrayOutputStream out, String value) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.ISO_8859_1);
        writeInt32(out, bytes.length);
        out.writeBytes(bytes);
        if ((bytes.length & 1) == 1) {
            out.write(0);
        }
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
}
