package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MultiuserXtraTest {

    @Test
    void tickDispatchesPendingBurstDuringDirectorPump() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        List<String> subjects = new ArrayList<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            Datum message = xtraRef.get().callHandler(1, "getNetMessage", List.of());
            subjects.add(((Datum.PropList) message).get("subject", true).toStr());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "A", Datum.of("one")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "B", Datum.of("two")));

        xtra.tick();

        assertEquals(2, callbacks.get());
        assertEquals(List.of("A", "B"), subjects);
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
    }

    @Test
    void tickLeavesOverflowBurstForLaterDirectorPumps() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        List<String> subjects = new ArrayList<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            Datum message = xtraRef.get().callHandler(1, "getNetMessage", List.of());
            subjects.add(((Datum.PropList) message).get("subject", true).toStr());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        for (int i = 0; i < 40; i++) {
            bridge.queue.add(new MultiuserNetBridge.NetMessage(
                    0, "", "msg-" + i, Datum.of("payload-" + i)));
        }

        xtra.tick();

        assertEquals(32, callbacks.get());
        assertEquals("msg-0", subjects.get(0));
        assertEquals("msg-31", subjects.get(31));

        xtra.tick();

        assertEquals(40, callbacks.get());
        assertEquals("msg-39", subjects.get(39));
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
    }

    @Test
    void checkNetMessagesDrainsLargePolledNetworkBurst() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        List<String> subjects = new ArrayList<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            Datum message = xtraRef.get().callHandler(1, "getNetMessage", List.of());
            subjects.add(((Datum.PropList) message).get("subject", true).toStr());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        for (int i = 0; i < 32; i++) {
            bridge.queue.add(new MultiuserNetBridge.NetMessage(
                    0, "", "msg-" + i, Datum.of("payload-" + i)));
        }

        assertEquals(32, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
        Datum processed = xtra.callHandler(1, "checkNetMessages", List.of(Datum.of(32)));

        assertEquals(32, processed.toInt());
        assertEquals(32, callbacks.get());
        assertEquals("msg-0", subjects.get(0));
        assertEquals("msg-31", subjects.get(31));
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
    }

    @Test
    void explicitWaitingPollLeavesQueuedMessagesForAuthoredCheckNetMessages() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        List<String> subjects = new ArrayList<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            Datum message = xtraRef.get().callHandler(1, "getNetMessage", List.of());
            subjects.add(((Datum.PropList) message).get("subject", true).toStr());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "room-data", Datum.of("payload")));

        assertEquals(1, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());

        xtra.tick();

        assertEquals(0, callbacks.get());
        assertEquals(List.of(), subjects);
        assertEquals(1, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());

        assertEquals(1, xtra.callHandler(1, "checkNetMessages", List.of(Datum.of(1))).toInt());
        assertEquals(1, callbacks.get());
        assertEquals(List.of("room-data"), subjects);
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());

        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "future-data", Datum.of("payload")));
        xtra.tick();

        assertEquals(1, callbacks.get());
        assertEquals(List.of("room-data"), subjects);
        assertEquals(1, xtra.callHandler(1, "checkNetMessages", List.of(Datum.of(1))).toInt());
        assertEquals(2, callbacks.get());
        assertEquals(List.of("room-data", "future-data"), subjects);
    }

    @Test
    void emptyWaitingPollSwitchesInstanceToAuthoredPolling() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        List<String> subjects = new ArrayList<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            Datum message = xtraRef.get().callHandler(1, "getNetMessage", List.of());
            subjects.add(((Datum.PropList) message).get("subject", true).toStr());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));

        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());

        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "later", Datum.of("payload")));
        xtra.tick();

        assertEquals(0, callbacks.get());
        assertEquals(List.of(), subjects);
        assertEquals(1, xtra.callHandler(1, "checkNetMessages", List.of(Datum.of(1))).toInt());
        assertEquals(1, callbacks.get());
        assertEquals(List.of("later"), subjects);
    }

    @Test
    void explicitCheckNetMessagesCanStillDrainRequestedCount() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            xtraRef.get().callHandler(1, "getNetMessage", List.of());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "A", Datum.of("one")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "B", Datum.of("two")));

        Datum processed = xtra.callHandler(1, "checkNetMessages", List.of(Datum.of(2)));

        assertEquals(2, processed.toInt());
        assertEquals(2, callbacks.get());
    }

    @Test
    void clearingMessageHandlerOnlyStopsCallbacksAndKeepsTransportOpen() {
        FakeBridge bridge = new FakeBridge();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {});

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        xtra.callHandler(1, "connectToNetServer",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"), Datum.of(1234)));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(-2, "System", "ConnectionProblem", Datum.of("")));

        xtra.callHandler(1, "setNetMessageHandler", List.of(Datum.VOID, Datum.VOID));
        xtra.tick();

        assertEquals(0, bridge.disconnects);
        assertEquals(0, bridge.polls);
        assertEquals(1, bridge.queue.size());

        xtra.callHandler(1, "sendNetMessage",
                List.of(Datum.of("*"), Datum.of("subject"), Datum.of("payload")));

        assertEquals(1, bridge.sends);
    }

    @Test
    void reconnectClearsMessagesAlreadyPolledIntoTheXtraInstance() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> callbacks.incrementAndGet());

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler", List.of(Datum.VOID, Datum.VOID));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(-2, "System", "ConnectionProblem", Datum.of("")));

        assertEquals(1, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());

        xtra.callHandler(1, "connectToNetServer",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"), Datum.of(1234)));
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        xtra.tick();

        assertEquals(0, callbacks.get());
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
    }

    @Test
    void reconnectRestoresAutomaticCallbacksUntilAuthoredPollingIsUsedAgain() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            callbacks.incrementAndGet();
            xtraRef.get().callHandler(1, "getNetMessage", List.of());
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());

        xtra.callHandler(1, "connectToNetServer",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"), Datum.of(1234)));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "", "after-reconnect", Datum.of("payload")));
        xtra.tick();

        assertEquals(1, callbacks.get());
    }

    @Test
    void connectPassesAuthoredModeFlagToBridge() {
        FakeBridge bridge = new FakeBridge();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {});

        xtra.createInstance(List.of());
        xtra.callHandler(1, "connectToNetServer",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"),
                        Datum.of(1234), Datum.of("*"), Datum.of(1)));

        assertEquals(1, bridge.lastModeFlag);
    }

    @Test
    void protectedSelectorResolutionRoutesOpenAndSendBySignature() {
        FakeBridge bridge = new FakeBridge();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {});

        xtra.createInstance(List.of());
        xtra.callHandler(1, "nativeBufferLimits",
                List.of(Datum.of(16 * 1024), Datum.of(100 * 1024), Datum.of(100)));
        xtra.callHandler(1, "nativeMessageHandler",
                List.of(Datum.symbol("msghandler"), Datum.of("target")));
        xtra.callHandler(1, "nativeConnect",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"),
                        Datum.of(1234), Datum.of("*"), Datum.of(1)));
        xtra.callHandler(1, "nativeSend",
                List.of(Datum.of("*"), Datum.of("CHAT"), Datum.of("hello")));

        assertEquals("example.test", bridge.lastHost);
        assertEquals(1234, bridge.lastPort);
        assertEquals(1, bridge.lastModeFlag);
        assertEquals(1, bridge.sends);
        assertEquals("CHAT", bridge.lastSubject);
        assertEquals("hello", bridge.lastContent.toStr());
    }

    @Test
    void protectedSelectorResolutionCanReadCurrentMessageBySignature() {
        FakeBridge bridge = new FakeBridge();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        AtomicReference<Datum.PropList> delivered = new AtomicReference<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            Datum message = xtraRef.get().callHandler(1, "nativeGetMessage", List.of());
            delivered.set((Datum.PropList) message);
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "connectToNetServer",
                List.of(Datum.symbol("msghandler"), Datum.of("target")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "System",
                "ConnectToNetServer", Datum.of("payload")));

        xtra.tick();

        Datum.PropList message = delivered.get();
        assertNotNull(message);
        assertEquals(0, message.get("errorCode", true).toInt());
        assertEquals(0, message.get("Crypto_DecryptHeader", true).toInt());
        assertEquals(0, message.get("txtColor", true).toInt());
        assertEquals("System", message.get("senderID", true).toStr());
        assertEquals("ConnectToNetServer", message.get("subject", true).toStr());
        assertEquals("ConnectToNetServer", message.get("tSubject", true).toStr());
        assertEquals("ConnectToNetServer", message.get("strechV", true).toStr());
        assertEquals("payload", message.get("content", true).toStr());
        assertEquals("payload", message.get("systemMac", true).toStr());
        assertEquals("*", ((Datum.List) message.get("recipients", true)).items().get(0).toStr());
        assertEquals(0, message.get("timeStamp", true).toInt());
    }

    @Test
    void protectedSelectorResolutionOpenSendPollAndExposeNetMessageFields() {
        FakeBridge bridge = new FakeBridge();
        AtomicReference<MultiuserXtra> xtraRef = new AtomicReference<>();
        AtomicReference<Datum.PropList> delivered = new AtomicReference<>();

        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {
            Datum message = xtraRef.get().callHandler(1, "nativeGetMessage", List.of());
            delivered.set((Datum.PropList) message);
        });
        xtraRef.set(xtra);

        xtra.createInstance(List.of());
        xtra.callHandler(1, "nativeBufferLimits",
                List.of(Datum.of(16 * 1024), Datum.of(100 * 1024), Datum.of(100)));
        xtra.callHandler(1, "nativeMessageHandler",
                List.of(Datum.symbol("tPowTbl"), Datum.of("target")));
        xtra.callHandler(1, "nativeConnect",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"),
                        Datum.of(40001), Datum.of("*"), Datum.of(1)));
        xtra.callHandler(1, "nativeSend",
                List.of(Datum.ZERO, Datum.ZERO, Datum.of("raw-packet")));

        assertEquals("example.test", bridge.lastHost);
        assertEquals(40001, bridge.lastPort);
        assertEquals(1, bridge.lastModeFlag);
        assertEquals(1, bridge.sends);
        assertEquals("0", bridge.lastSubject);
        assertEquals("raw-packet", bridge.lastContent.toStr());

        bridge.queue.add(new MultiuserNetBridge.NetMessage(
                0, "System", "ConnectToNetServer", Datum.of("server-packet")));

        assertEquals(1, xtra.callHandler(1, "nativeWaitingCount", List.of()).toInt());
        assertEquals(1, xtra.callHandler(1, "nativeCheckMessages", List.of(Datum.of(1))).toInt());

        Datum.PropList message = delivered.get();
        assertNotNull(message);
        assertEquals(0, message.get("errorCode", true).toInt());
        assertEquals(0, message.get("Crypto_DecryptHeader", true).toInt());
        assertEquals(0, message.get("txtColor", true).toInt());
        assertEquals("System", message.get("senderID", true).toStr());
        assertEquals("ConnectToNetServer", message.get("subject", true).toStr());
        assertEquals("ConnectToNetServer", message.get("tSubject", true).toStr());
        assertEquals("ConnectToNetServer", message.get("strechV", true).toStr());
        assertEquals("server-packet", message.get("content", true).toStr());
        assertEquals("server-packet", message.get("systemMac", true).toStr());
        assertEquals("*", ((Datum.List) message.get("recipients", true)).items().get(0).toStr());
        assertEquals(0, message.get("timeStamp", true).toInt());
    }

    @Test
    void breakConnectionRequestsTransportDisconnectAndClearsQueuedMessages() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger callbacks = new AtomicInteger();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> callbacks.incrementAndGet());

        xtra.createInstance(List.of());
        xtra.callHandler(1, "setNetMessageHandler",
                List.of(Datum.of("onMessage"), Datum.of("target")));
        bridge.queue.add(new MultiuserNetBridge.NetMessage(0, "System", "pending", Datum.of("payload")));

        assertEquals(1, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
        xtra.callHandler(1, "breakConnection", List.of(Datum.of("*")));
        xtra.tick();

        assertEquals(1, bridge.disconnects);
        assertEquals(0, callbacks.get());
        assertEquals(0, xtra.callHandler(1, "getNumberWaitingNetMessages", List.of()).toInt());
    }

    @Test
    void multiuserClientSideStatusHandlersExposeDirectorCompatibleDefaults() {
        FakeBridge bridge = new FakeBridge();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {});

        xtra.createInstance(List.of());

        assertEquals("", xtra.callHandler(1, "getNetAddressCookie", List.of()).toStr());
        assertEquals(0, ((Datum.List) xtra.callHandler(1, "getPeerConnectionList", List.of())).items().size());
        assertEquals(0, xtra.callHandler(1, "getNetOutgoingBytes", List.of()).toInt());
        assertEquals(-1, xtra.callHandler(1, "waitForNetConnection", List.of()).toInt());
    }

    private static final class FakeBridge implements MultiuserNetBridge {
        private final List<NetMessage> queue = new ArrayList<>();
        int disconnects;
        int polls;
        int sends;
        int lastModeFlag;
        String lastHost;
        int lastPort;
        String lastSubject;
        Datum lastContent = Datum.VOID;

        @Override
        public void requestConnect(int instanceId, String host, int port) {
            lastHost = host;
            lastPort = port;
        }

        @Override
        public void requestConnect(int instanceId, String host, int port, int modeFlag) {
            lastHost = host;
            lastPort = port;
            lastModeFlag = modeFlag;
        }

        @Override
        public void requestSend(int instanceId, String senderID, String subject, Datum content) {
            sends++;
            lastSubject = subject;
            lastContent = content;
        }

        @Override
        public void requestDisconnect(int instanceId) {
            disconnects++;
            queue.clear();
        }

        @Override
        public boolean isConnected(int instanceId) {
            return true;
        }

        @Override
        public List<NetMessage> pollMessages(int instanceId) {
            polls++;
            List<NetMessage> messages = List.copyOf(queue);
            queue.clear();
            return messages;
        }

        @Override
        public void destroyInstance(int instanceId) {
        }
    }
}
