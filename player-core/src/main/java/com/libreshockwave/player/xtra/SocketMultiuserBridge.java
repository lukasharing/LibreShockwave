package com.libreshockwave.player.xtra;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.xtra.MultiuserTransportCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Socket-based MultiuserNetBridge using java.net.Socket.
 * Connects on a background thread; pollMessages reads non-blocking via available().
 * <p>
 * Kepler sends/receives raw strings over TCP (no binary length-prefix framing).
 * Each complete chunk of available data is delivered as one message with the
 * raw content in the content field.
 */
public class SocketMultiuserBridge implements MultiuserNetBridge {

    private static class Connection {
        Socket socket;
        InputStream in;
        OutputStream out;
        boolean connected;
        boolean connecting;
        boolean contentOnlyTransport;
        boolean smusTransport;
        String smusInboundBuffer = "";
        final byte[] readBuf = new byte[8192];
    }

    private final Map<Integer, Connection> connections = new HashMap<>();

    @Override
    public void requestConnect(int instanceId, String host, int port) {
        requestConnect(instanceId, host, port, 0);
    }

    @Override
    public void requestConnect(int instanceId, String host, int port, int modeFlag) {
        Connection conn = new Connection();
        conn.connecting = true;
        conn.contentOnlyTransport = modeFlag != 0;
        conn.smusTransport = modeFlag == 0;
        connections.put(instanceId, conn);

        Thread t = new Thread(() -> {
            try {
                Socket socket = new Socket(host, port);
                conn.socket = socket;
                conn.in = socket.getInputStream();
                conn.out = socket.getOutputStream();
                conn.connected = true;
            } catch (IOException e) {
                System.err.println("[SocketMultiuserBridge] Connect failed: " + e.getMessage());
            } finally {
                conn.connecting = false;
            }
        }, "MUS-connect-" + instanceId);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void requestSend(int instanceId, String senderID, String subject, Datum content) {
        Connection conn = connections.get(instanceId);
        if (conn == null || !conn.connected) return;

        String contentString = content.toStr();
        String payload = conn.contentOnlyTransport
                || MultiuserTransportCodec.isContentOnlyEnvelope(senderID, subject)
                ? contentString
                : MultiuserTransportCodec.encodeSmusPacket(senderID, subject, contentString);
        byte[] raw = payload.getBytes(StandardCharsets.ISO_8859_1);
        try {
            conn.out.write(raw);
            conn.out.flush();
        } catch (IOException e) {
            System.err.println("[SocketMultiuserBridge] Send failed: " + e.getMessage());
        }
    }

    @Override
    public void requestDisconnect(int instanceId) {
        Connection conn = connections.remove(instanceId);
        if (conn != null && conn.socket != null) {
            try { conn.socket.close(); } catch (IOException ignored) {}
            conn.connected = false;
        }
    }

    @Override
    public boolean isConnected(int instanceId) {
        Connection conn = connections.get(instanceId);
        return conn != null && conn.connected;
    }

    @Override
    public List<NetMessage> pollMessages(int instanceId) {
        Connection conn = connections.get(instanceId);
        if (conn == null || !conn.connected) return List.of();

        // Read whatever is available without blocking
        try {
            int avail = conn.in.available();
            if (avail > 0) {
                int toRead = Math.min(avail, conn.readBuf.length);
                int read = conn.in.read(conn.readBuf, 0, toRead);
                if (read == -1) {
                    conn.connected = false;
                    return List.of();
                }
                String data = new String(conn.readBuf, 0, read, StandardCharsets.ISO_8859_1);
                if (conn.smusTransport) {
                    conn.smusInboundBuffer += data;
                    MultiuserTransportCodec.SmusParseResult result =
                            MultiuserTransportCodec.parseSmusPackets(conn.smusInboundBuffer);
                    if (result.messages().isEmpty()) {
                        return List.of();
                    }
                    if (result.consumedChars() < conn.smusInboundBuffer.length()) {
                        conn.smusInboundBuffer = conn.smusInboundBuffer.substring(result.consumedChars());
                    } else {
                        conn.smusInboundBuffer = "";
                    }
                    List<NetMessage> messages = new ArrayList<>();
                    for (MultiuserTransportCodec.SmusMessage msg : result.messages()) {
                        messages.add(new NetMessage(
                                msg.errorCode(), msg.senderID(), msg.subject(), new Datum.Str(msg.content())));
                    }
                    return messages;
                }
                return List.of(new NetMessage(0, "", "", new Datum.Str(data)));
            }
        } catch (IOException e) {
            return List.of();
        }

        return List.of();
    }

    @Override
    public void destroyInstance(int instanceId) {
        requestDisconnect(instanceId);
    }
}
