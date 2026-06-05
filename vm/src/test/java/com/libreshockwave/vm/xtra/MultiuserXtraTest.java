package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiuserXtraTest {

    @Test
    void tickPollsPendingBurstAndInvokesCallbacks() {
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
    void connectPassesAuthoredModeFlagToBridge() {
        FakeBridge bridge = new FakeBridge();
        MultiuserXtra xtra = new MultiuserXtra(bridge, (target, handlerName, args) -> {});

        xtra.createInstance(List.of());
        xtra.callHandler(1, "connectToNetServer",
                List.of(Datum.of("*"), Datum.of("*"), Datum.of("example.test"),
                        Datum.of(1234), Datum.of("*"), Datum.of(1)));

        assertEquals(1, bridge.lastModeFlag);
    }

    private static final class FakeBridge implements MultiuserNetBridge {
        private final List<NetMessage> queue = new ArrayList<>();
        int disconnects;
        int polls;
        int sends;
        int lastModeFlag;

        @Override
        public void requestConnect(int instanceId, String host, int port) {
        }

        @Override
        public void requestConnect(int instanceId, String host, int port, int modeFlag) {
            lastModeFlag = modeFlag;
        }

        @Override
        public void requestSend(int instanceId, String senderID, String subject, Datum content) {
            sends++;
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
