package com.libreshockwave.player.wasm;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
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
}
