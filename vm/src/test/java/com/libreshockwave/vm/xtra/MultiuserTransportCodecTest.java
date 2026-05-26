package com.libreshockwave.vm.xtra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiuserTransportCodecTest {

    @Test
    void encodesAndDecodesStringMessagePacket() {
        String packet = MultiuserTransportCodec.encodeSmusPacket("*", "LOGIN", "ticket");

        MultiuserTransportCodec.SmusParseResult parsed =
                MultiuserTransportCodec.parseSmusPackets(packet);

        assertEquals(packet.length(), parsed.consumedChars());
        assertEquals(1, parsed.messages().size());
        assertEquals("LOGIN", parsed.messages().get(0).subject());
        assertEquals("ticket", parsed.messages().get(0).content());
    }

    @Test
    void parsesMultipleCompletePacketsAndLeavesIncompleteRemainder() {
        String first = MultiuserTransportCodec.encodeSmusPacket("!", "PING", "0");
        String second = MultiuserTransportCodec.encodeSmusPacket("!", "HELLO", "12345");
        String partial = MultiuserTransportCodec.encodeSmusPacket("!", "WAIT", "later").substring(0, 10);

        MultiuserTransportCodec.SmusParseResult parsed =
                MultiuserTransportCodec.parseSmusPackets(first + second + partial);

        assertEquals(2, parsed.messages().size());
        assertEquals(first.length() + second.length(), parsed.consumedChars());
        assertEquals("PING", parsed.messages().get(0).subject());
        assertEquals("0", parsed.messages().get(0).content());
        assertEquals("HELLO", parsed.messages().get(1).subject());
        assertEquals("12345", parsed.messages().get(1).content());
    }

    @Test
    void parsesCapturedServerPingPacket() {
        String packet = latin1FromHex(
                "72000000002c000000006a1496490000000450494e470000000653797374656d"
                        + "000000010000000121000003000000013000");

        MultiuserTransportCodec.SmusParseResult parsed =
                MultiuserTransportCodec.parseSmusPackets(packet);

        assertEquals(packet.length(), parsed.consumedChars());
        assertEquals(1, parsed.messages().size());
        MultiuserTransportCodec.SmusMessage message = parsed.messages().get(0);
        assertEquals(0, message.errorCode());
        assertEquals("System", message.senderID());
        assertEquals("PING", message.subject());
        assertEquals("0", message.content());
        assertEquals(MultiuserTransportCodec.SMUS_STRING, message.contentType());
    }

    @Test
    void detectsDirectorContentOnlyEnvelope() {
        assertTrue(MultiuserTransportCodec.isContentOnlyEnvelope("0", "0"));
    }

    private static String latin1FromHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
