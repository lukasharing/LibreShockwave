package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
