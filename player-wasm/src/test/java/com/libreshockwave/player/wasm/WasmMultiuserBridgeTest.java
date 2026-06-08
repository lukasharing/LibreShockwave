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
    void pendingDrainSnapshotsRequestsAndLeavesNewRequestsQueued() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestSend(1, "0", "0", new Datum.Str("@@FIRST"));

        assertEquals(1, bridge.beginPendingRequestDrain());
        bridge.requestSend(1, "0", "0", new Datum.Str("@@SECOND"));

        assertEquals("@@FIRST", bridge.getRequest(0).content);
        assertEquals(1, bridge.getPendingRequests().size());
        assertEquals("@@SECOND", bridge.getPendingRequests().get(0).content);

        bridge.finishPendingRequestDrain();

        assertEquals(1, bridge.beginPendingRequestDrain());
        assertEquals("@@SECOND", bridge.getRequest(0).content);
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
    void contentOnlyConnectionPreservesLargeIncomingBurst() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 1);
        String first = "A".repeat(666);
        String second = "B".repeat(3480);
        String third = "C".repeat(52);

        bridge.deliverMessage(1, 0, "", "", first);
        bridge.deliverMessage(1, 0, "", "", second);
        bridge.deliverMessage(1, 0, "", "", third);

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(3, messages.size());
        assertEquals(first, messages.get(0).content().toStr());
        assertEquals(second, messages.get(1).content().toStr());
        assertEquals(third, messages.get(2).content().toStr());
    }

    @Test
    void multipleContentOnlyInstancesKeepMessagesAndSendsIsolated() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "first.example.test", 1001, 1);
        bridge.requestConnect(2, "second.example.test", 1002, 1);
        bridge.drainPendingRequests();

        bridge.deliverMessage(1, 0, "", "", "first-in");
        bridge.deliverMessage(2, 0, "", "", "second-in");

        assertEquals("first-in", bridge.pollMessages(1).get(0).content().toStr());
        assertEquals("second-in", bridge.pollMessages(2).get(0).content().toStr());

        bridge.requestSend(2, "0", "0", new Datum.Str("second-out"));
        bridge.requestSend(1, "0", "0", new Datum.Str("first-out"));

        List<WasmMultiuserBridge.PendingRequest> requests = bridge.getPendingRequests();
        assertEquals(2, requests.size());
        assertEquals(2, requests.get(0).instanceId);
        assertEquals("second-out", requests.get(0).content);
        assertEquals(1, requests.get(1).instanceId);
        assertEquals("first-out", requests.get(1).content);
    }

    @Test
    void connectNotificationUsesVoidContentLikeDirectorMultiuserXtra() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyConnected(1);

        List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
        assertEquals(1, messages.size());
        assertEquals(0, messages.get(0).errorCode());
        assertEquals("ConnectToNetServer", messages.get(0).subject());
        assertEquals(true, messages.get(0).content().isVoid());
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
    void cleanWebSocketCloseQueuesConnectionProblem() {
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
    void cleanCloseKeepsAlreadyQueuedApplicationDataBeforeTerminalError() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 1);
        bridge.drainPendingRequests();
        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.deliverMessage(1, 0, "", "", "strip-response");
        bridge.notifyDisconnected(1, 1000, true, "close code=1000");

        List<MultiuserNetBridge.NetMessage> applicationMessages = bridge.pollMessages(1);
        assertEquals(1, applicationMessages.size());
        assertEquals("strip-response", applicationMessages.get(0).content().toStr());

        List<MultiuserNetBridge.NetMessage> terminalMessages = bridge.pollMessages(1);
        assertEquals(1, terminalMessages.size());
        assertEquals(-2, terminalMessages.get(0).errorCode());
        assertEquals("ConnectionProblem", terminalMessages.get(0).subject());
        assertEquals(true, terminalMessages.get(0).content().toStr().contains("closeCode=1000"));
        assertEquals(false, bridge.isConnected(1));
    }

    @Test
    void uncleanCloseKeepsAlreadyQueuedApplicationDataBeforeTerminalError() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 1);
        bridge.drainPendingRequests();
        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.deliverMessage(1, 0, "", "", "@@article-list");
        bridge.notifyDisconnected(1, 1006, false, "close code=1006");

        List<MultiuserNetBridge.NetMessage> applicationMessages = bridge.pollMessages(1);
        assertEquals(1, applicationMessages.size());
        assertEquals("@@article-list", applicationMessages.get(0).content().toStr());

        List<MultiuserNetBridge.NetMessage> terminalMessages = bridge.pollMessages(1);
        assertEquals(1, terminalMessages.size());
        assertEquals(-2, terminalMessages.get(0).errorCode());
        assertEquals("ConnectionProblem", terminalMessages.get(0).subject());
        assertEquals(true, terminalMessages.get(0).content().toStr().contains("closeCode=1006"));
    }

    @Test
    void sendAfterCleanCloseDoesNotQueueBrowserSend() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "example.test", 1234, 1);
        bridge.drainPendingRequests();
        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.notifyDisconnected(1, 1000, true, "close code=1000");
        bridge.pollMessages(1);

        bridge.requestSend(1, "0", "0", new Datum.Str("@@GET_ARTICLE"));

        List<WasmMultiuserBridge.PendingRequest> requests = bridge.getPendingRequests();
        assertEquals(List.of(), requests);
    }

    @Test
    void sendAfterUncleanCloseDoesNotQueueBrowserSend() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.notifyConnected(1);
        bridge.pollMessages(1);
        bridge.notifyDisconnected(1, 1006, false, "");
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
    void reconnectDropsStalePendingSendsForSameInstance() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestConnect(1, "old.example.test", 1111, 1);
        bridge.requestSend(1, "0", "0", new Datum.Str("old-packet"));
        bridge.requestConnect(1, "new.example.test", 2222, 1);
        bridge.requestSend(1, "0", "0", new Datum.Str("new-packet"));

        List<WasmMultiuserBridge.PendingRequest> requests = bridge.getPendingRequests();
        assertEquals(2, requests.size());
        assertEquals(WasmMultiuserBridge.REQ_CONNECT, requests.get(0).type);
        assertEquals("new.example.test", requests.get(0).host);
        assertEquals(2222, requests.get(0).port);
        assertEquals(WasmMultiuserBridge.REQ_SEND, requests.get(1).type);
        assertEquals("new-packet", requests.get(1).content);
    }

    @Test
    void reconnectDuringPendingDrainDropsStaleSnapshotRequestsForSameInstance() {
        WasmMultiuserBridge bridge = new WasmMultiuserBridge();

        bridge.requestSend(1, "0", "0", new Datum.Str("old-packet"));
        assertEquals(1, bridge.beginPendingRequestDrain());

        bridge.requestConnect(1, "new.example.test", 2222, 1);

        assertEquals(WasmMultiuserBridge.REQ_CONNECT, bridge.getRequest(0).type);
        assertEquals("new.example.test", bridge.getRequest(0).host);
        assertEquals(1, bridge.getPendingRequests().size());
        assertEquals(WasmMultiuserBridge.REQ_CONNECT, bridge.getPendingRequests().get(0).type);
        assertEquals("new.example.test", bridge.getPendingRequests().get(0).host);
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
