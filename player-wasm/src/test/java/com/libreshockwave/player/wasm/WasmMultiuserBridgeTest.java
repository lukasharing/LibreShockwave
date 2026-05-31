package com.libreshockwave.player.wasm;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.xtra.MultiuserTransportCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WasmMultiuserBridgeTest {

    @Test
    void plaintextKeepaliveStillReachesLingoHandler() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.deliverMessage(1, 0, "", "", "@r\u0001");

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(1, messages.size());
        assertEquals("@r\u0001", messages.get(0).content().toStr());
    }

    @Test
    void initialPlaintextKeepaliveDoesNotSuppressPong() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.deliverMessage(1, 0, "", "", "@r\u0001");
        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCD"));

        List<WasmMultiuserBridge.PendingRequest> messages = bridge.getPendingRequests();
        assertEquals(1, messages.size());
        assertEquals("@@BCD", messages.get(0).content);
    }

    @Test
    void plaintextKeepaliveAfterSessionTrafficDoesNotFilterOutboundMessages() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.deliverMessage(1, 0, "", "", "session-payload");
        bridge.deliverMessage(1, 0, "", "", "@r\u0001");
        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCD"));

        List<WasmMultiuserBridge.PendingRequest> messages = bridge.getPendingRequests();
        assertEquals(1, messages.size());
        assertEquals("@@BCD", messages.get(0).content);
    }

    @Test
    void plaintextKeepaliveAfterSessionTrafficAllowsNonMatchingSend() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.deliverMessage(1, 0, "", "", "session-payload");
        bridge.deliverMessage(1, 0, "", "", "@r\u0001");
        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCN"));

        List<WasmMultiuserBridge.PendingRequest> messages = bridge.getPendingRequests();
        assertEquals(1, messages.size());
        assertEquals("@@BCN", messages.get(0).content);
    }

    @Test
    void plaintextPongQueuesWhenNoKeepaliveWasSeen() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCD"));

        List<WasmMultiuserBridge.PendingRequest> messages = bridge.getPendingRequests();
        assertEquals(1, messages.size());
        assertEquals("@@BCD", messages.get(0).content);
    }

    @Test
    void contentOnlyConnectionPreservesRawOutgoingPayload() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 1);
        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCD"));

        List<WasmMultiuserBridge.PendingRequest> messages = bridge.getPendingRequests();
        assertEquals(2, messages.size());
        assertEquals("@@BCD", messages.get(1).content);
    }

    @Test
    void smusConnectionEncodesSubjectAndContentForRawTransport() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 0);
        bridge.requestSend(1, "*", "LOGIN", new Datum.Str("ticket-value"));
        bridge.requestSend(1, "*", "PONG", new Datum.Str(""));

        List<WasmMultiuserBridge.PendingRequest> messages = bridge.getPendingRequests();
        assertEquals("LOGIN", MultiuserTransportCodec.parseSmusPackets(messages.get(1).content)
                .messages().get(0).subject());
        assertEquals("ticket-value", MultiuserTransportCodec.parseSmusPackets(messages.get(1).content)
                .messages().get(0).content());
        assertEquals("PONG", MultiuserTransportCodec.parseSmusPackets(messages.get(2).content)
                .messages().get(0).subject());
    }

    @Test
    void smusConnectionSplitsIncomingSubjectAndContent() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 0);
        bridge.deliverMessage(1, 0, "", "",
                MultiuserTransportCodec.encodeSmusPacket("!", "HELLO", "session-id")
                        + MultiuserTransportCodec.encodeSmusPacket("!", "PING", ""));

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals("HELLO", messages.get(0).subject());
        assertEquals("session-id", messages.get(0).content().toStr());
        assertEquals("PING", messages.get(1).subject());
        assertEquals("", messages.get(1).content().toStr());
    }

    @Test
    void smusConnectionFallsBackToRawWhenServerSpeaksContentOnly() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 0);
        bridge.deliverMessage(1, 0, "", "", "@@raw-fuse-payload");
        bridge.requestSend(1, "*", "GET_PAGE_ARTICLES", new Datum.Str("@@encoded-command"));

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(1, messages.size());
        assertEquals("", messages.get(0).subject());
        assertEquals("@@raw-fuse-payload", messages.get(0).content().toStr());

        List<WasmMultiuserBridge.PendingRequest> requests = bridge.getPendingRequests();
        assertEquals("@@encoded-command", requests.get(1).content);
    }

    @Test
    void smusConnectionKeepsSplitPacketPrefixBeforeRawFallback() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 0);
        bridge.deliverMessage(1, 0, "", "", "r");
        assertEquals(List.of(), bridge.pollMessages(1));

        bridge.deliverMessage(1, 0, "", "", "@raw-fuse-payload");

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(1, messages.size());
        assertEquals("r@raw-fuse-payload", messages.get(0).content().toStr());
    }

    @Test
    void contentOnlyConnectionKeepsIncomingPayloadInContent() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 1);
        String payload = MultiuserTransportCodec.encodeSmusPacket("!", "HELLO", "session-id");
        bridge.deliverMessage(1, 0, "", "", payload);

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals("", messages.get(0).subject());
        assertEquals(payload, messages.get(0).content().toStr());
    }

    @Test
    void expectedDisconnectDoesNotQueueConnectionProblem() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.requestDisconnect(1);
        bridge.notifyError(1, -2);
        bridge.notifyDisconnected(1);

        assertEquals(List.of(), bridge.pollMessages(1));
    }

    @Test
    void reconnectClearsExpectedDisconnectState() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestDisconnect(1);
        bridge.notifyDisconnected(1);
        bridge.requestConnect(1, "example.test", 1234);
        bridge.notifyDisconnected(1);

        assertEquals(1, bridge.pollMessages(1).size());
    }

    @Test
    void requestConnectClearsOldConnectedAndQueuedTerminalMessages() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyConnected(1);
        assertEquals(true, bridge.isConnected(1));
        bridge.notifyDisconnected(1);
        bridge.requestConnect(1, "example.test", 1234);

        assertEquals(false, bridge.isConnected(1));
        assertEquals(List.of(), bridge.pollMessages(1));
    }

    @Test
    void requestDisconnectClearsStaleConnectionProblem() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyDisconnected(1);
        bridge.requestDisconnect(1);

        assertEquals(List.of(), bridge.pollMessages(1));
    }

    @Test
    void unexpectedCleanWebSocketCloseQueuesConnectionProblem() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.notifyDisconnected(1, 1000, true,
                "close code=1000 wasClean=true url=ws://127.0.0.1:4173/mus-ws");

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(1, messages.size());
        assertEquals(-2, messages.get(0).errorCode());
        assertEquals("ConnectionProblem", messages.get(0).subject());
        assertEquals(true, messages.get(0).content().toStr().contains("closeCode=1000"));
        assertEquals(true, messages.get(0).content().toStr().contains("wasClean=true"));
        assertEquals(false, bridge.isConnected(1));
    }

    @Test
    void sendAfterUnexpectedCloseDoesNotQueueBrowserSend() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.notifyDisconnected(1, 1000, true, "");
        assertEquals(1, bridge.pollMessages(1).size());
        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCD"));

        List<WasmMultiuserBridge.PendingRequest> requests = bridge.getPendingRequests();
        assertEquals(List.of(), requests);
    }

    @Test
    void reconnectClearsTerminalSendGuard() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyDisconnected(1, 1000, true, "");
        bridge.requestConnect(1, "example.test", 1234, 1);
        bridge.requestSend(1, "0", "0", new Datum.Str("@@BCD"));

        List<WasmMultiuserBridge.PendingRequest> requests = bridge.getPendingRequests();
        assertEquals(2, requests.size());
        assertEquals(WasmMultiuserBridge.REQ_CONNECT, requests.get(0).type);
        assertEquals("@@BCD", requests.get(1).content);
    }

    @Test
    void connectionProblemKeepsHostDiagnosticDetailInContent() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyDisconnected(1, 1006, false,
                "close code=1006 wasClean=false url=ws://127.0.0.1:4173/mus-ws");

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(1, messages.size());
        assertEquals(-2, messages.get(0).errorCode());
        assertEquals("ConnectionProblem", messages.get(0).subject());
        String detail = messages.get(0).content().toStr();
        assertEquals(true, detail.contains("closeCode=1006"));
        assertEquals(true, detail.contains("wasClean=false"));
    }
}
