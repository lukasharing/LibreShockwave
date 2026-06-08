package com.libreshockwave.player.xtra;

import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketMultiuserBridgeTest {

    @Test
    void connectQueuesDirectorConnectMessage() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Socket> accepted = accept(server);
            SocketMultiuserBridge bridge = new SocketMultiuserBridge();

            bridge.requestConnect(1, "127.0.0.1", server.getLocalPort(), 1);
            waitConnected(bridge, 1);

            List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(1);
            assertEquals(1, messages.size());
            assertEquals(0, messages.get(0).errorCode());
            assertEquals("ConnectToNetServer", messages.get(0).subject());
            assertTrue(messages.get(0).content().isVoid());

            bridge.destroyInstance(1);
            accepted.get(1, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void sendBeforeAndAfterConnectUseSamePersistentSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[32];
                    while (out.toString(StandardCharsets.ISO_8859_1).length() < "firstsecond".length()) {
                        int read = socket.getInputStream().read(buffer);
                        if (read < 0) break;
                        out.write(buffer, 0, read);
                    }
                    return out.toString(StandardCharsets.ISO_8859_1);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            SocketMultiuserBridge bridge = new SocketMultiuserBridge();

            bridge.requestConnect(1, "127.0.0.1", server.getLocalPort(), 1);
            bridge.requestSend(1, "0", "0", Datum.of("first"));
            waitConnected(bridge, 1);
            bridge.pollMessages(1);
            bridge.requestSend(1, "0", "0", Datum.of("second"));

            assertEquals("firstsecond", received.get(2, TimeUnit.SECONDS));
            bridge.destroyInstance(1);
        }
    }

    @Test
    void inboundDataIsDeliveredOnlyToOwningInstance() throws Exception {
        try (ServerSocket firstServer = new ServerSocket(0);
             ServerSocket secondServer = new ServerSocket(0)) {
            CompletableFuture<Socket> firstAccepted = acceptAndWrite(firstServer, "one");
            CompletableFuture<Socket> secondAccepted = acceptAndWrite(secondServer, "two");
            SocketMultiuserBridge bridge = new SocketMultiuserBridge();

            bridge.requestConnect(1, "127.0.0.1", firstServer.getLocalPort(), 1);
            bridge.requestConnect(2, "127.0.0.1", secondServer.getLocalPort(), 1);
            waitConnected(bridge, 1);
            waitConnected(bridge, 2);

            List<MultiuserNetBridge.NetMessage> firstMessages = waitForApplicationMessage(bridge, 1);
            List<MultiuserNetBridge.NetMessage> secondMessages = waitForApplicationMessage(bridge, 2);

            assertEquals("one", firstMessages.get(0).content().toStr());
            assertEquals("two", secondMessages.get(0).content().toStr());

            bridge.destroyInstance(1);
            bridge.destroyInstance(2);
            firstAccepted.get(1, TimeUnit.SECONDS).close();
            secondAccepted.get(1, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void upstreamCloseQueuesTerminalMessageForThatInstance() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
                try (Socket ignored = server.accept()) {
                    // Closing the accepted socket simulates an upstream TCP close.
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            SocketMultiuserBridge bridge = new SocketMultiuserBridge();

            bridge.requestConnect(1, "127.0.0.1", server.getLocalPort(), 1);
            accepted.get(1, TimeUnit.SECONDS);

            List<MultiuserNetBridge.NetMessage> messages = waitForTerminalMessage(bridge, 1);
            assertEquals(1, messages.size());
            assertEquals(-2, messages.get(0).errorCode());
            assertEquals("ConnectionProblem", messages.get(0).subject());
            assertTrue(messages.get(0).content().toStr().contains("socket closed"));

            bridge.destroyInstance(1);
        }
    }

    private static CompletableFuture<Socket> accept(ServerSocket server) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return server.accept();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static CompletableFuture<Socket> acceptAndWrite(ServerSocket server, String text) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Socket socket = server.accept();
                socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                return socket;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void waitConnected(SocketMultiuserBridge bridge, int instanceId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (bridge.isConnected(instanceId)) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(bridge.isConnected(instanceId), "socket did not connect");
    }

    private static List<MultiuserNetBridge.NetMessage> waitForApplicationMessage(
            SocketMultiuserBridge bridge, int instanceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(instanceId).stream()
                    .filter(message -> !"ConnectToNetServer".equals(message.subject()))
                    .toList();
            if (!messages.isEmpty()) {
                return messages;
            }
            Thread.sleep(10);
        }
        return List.of();
    }

    private static List<MultiuserNetBridge.NetMessage> waitForTerminalMessage(
            SocketMultiuserBridge bridge, int instanceId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<MultiuserNetBridge.NetMessage> messages = bridge.pollMessages(instanceId).stream()
                    .filter(message -> "ConnectionProblem".equals(message.subject()))
                    .toList();
            if (!messages.isEmpty()) {
                return messages;
            }
            Thread.sleep(10);
        }
        return List.of();
    }
}
