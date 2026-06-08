package com.libreshockwave.player.xtra;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.xtra.MultiuserTransportState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        boolean terminalQueued;
        MultiuserTransportState transport;
        final byte[] readBuf = new byte[8192];
        final List<byte[]> pendingWrites = new ArrayList<>();
        final List<NetMessage> pendingMessages = new ArrayList<>();
    }

    private final Map<Integer, Connection> connections = new ConcurrentHashMap<>();

    @Override
    public void requestConnect(int instanceId, String host, int port) {
        requestConnect(instanceId, host, port, 0);
    }

    @Override
    public void requestConnect(int instanceId, String host, int port, int modeFlag) {
        requestDisconnect(instanceId);
        Connection conn = new Connection();
        conn.connecting = true;
        conn.transport = new MultiuserTransportState(modeFlag);
        connections.put(instanceId, conn);

        Thread t = new Thread(() -> {
            try {
                Socket socket = new Socket(host, port);
                synchronized (conn) {
                    if (!isCurrentConnection(instanceId, conn)) {
                        closeQuietly(socket);
                        return;
                    }
                    conn.socket = socket;
                    conn.in = socket.getInputStream();
                    conn.out = socket.getOutputStream();
                    conn.connected = true;
                    conn.connecting = false;
                    queueMessage(conn, new NetMessage(0, "System", "ConnectToNetServer", Datum.VOID));
                    flushPendingWrites(conn);
                    startReaderThread(instanceId, conn);
                }
            } catch (IOException e) {
                queueTerminal(instanceId, conn, -3, "connect failed: " + e.getMessage());
            } finally {
                synchronized (conn) {
                    conn.connecting = false;
                }
            }
        }, "MUS-connect-" + instanceId);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void requestSend(int instanceId, String senderID, String subject, Datum content) {
        Connection conn = connections.get(instanceId);
        if (conn == null) return;

        String payload = conn.transport.encodeOutgoing(senderID, subject, content);
        byte[] raw = payload.getBytes(StandardCharsets.ISO_8859_1);
        synchronized (conn) {
            if (conn.terminalQueued) {
                return;
            }
            if (conn.connected && conn.out != null) {
                writeOrQueueTerminal(instanceId, conn, raw);
                return;
            }
            if (conn.connecting) {
                conn.pendingWrites.add(raw);
                return;
            }
        }
        queueTerminal(instanceId, conn, -2, "send failed: socket not connected");
    }

    @Override
    public void requestDisconnect(int instanceId) {
        Connection conn = connections.remove(instanceId);
        if (conn != null) {
            Socket socket;
            synchronized (conn) {
                socket = conn.socket;
                conn.connected = false;
                conn.connecting = false;
                conn.pendingWrites.clear();
                conn.pendingMessages.clear();
                conn.terminalQueued = true;
            }
            closeQuietly(socket);
        }
    }

    @Override
    public boolean isConnected(int instanceId) {
        Connection conn = connections.get(instanceId);
        if (conn == null) {
            return false;
        }
        synchronized (conn) {
            return conn.connected;
        }
    }

    @Override
    public List<NetMessage> pollMessages(int instanceId) {
        Connection conn = connections.get(instanceId);
        if (conn == null) return List.of();

        return drainPendingMessages(conn);
    }

    @Override
    public void destroyInstance(int instanceId) {
        requestDisconnect(instanceId);
    }

    private boolean isCurrentConnection(int instanceId, Connection conn) {
        return connections.get(instanceId) == conn;
    }

    private static void queueMessage(Connection conn, NetMessage message) {
        conn.pendingMessages.add(message);
    }

    private void queueTerminal(int instanceId, Connection conn, int errorCode, String detail) {
        if (!isCurrentConnection(instanceId, conn)) {
            return;
        }
        Socket socket = null;
        synchronized (conn) {
            if (conn.terminalQueued) {
                return;
            }
            conn.terminalQueued = true;
            conn.connected = false;
            conn.connecting = false;
            conn.pendingWrites.clear();
            socket = conn.socket;
            conn.socket = null;
            conn.in = null;
            conn.out = null;
            queueMessage(conn, new NetMessage(errorCode, "System", "ConnectionProblem",
                    new Datum.Str(detail != null ? detail : "")));
        }
        closeQuietly(socket);
    }

    private void flushPendingWrites(Connection conn) throws IOException {
        while (!conn.pendingWrites.isEmpty()) {
            byte[] raw = conn.pendingWrites.remove(0);
            conn.out.write(raw);
            conn.out.flush();
        }
    }

    private void startReaderThread(int instanceId, Connection conn) {
        Thread t = new Thread(() -> {
            while (isCurrentConnection(instanceId, conn)) {
                InputStream input;
                synchronized (conn) {
                    if (!conn.connected || conn.in == null) {
                        return;
                    }
                    input = conn.in;
                }
                try {
                    int read = input.read(conn.readBuf);
                    if (read < 0) {
                        queueTerminal(instanceId, conn, -2, "socket closed");
                        return;
                    }
                    if (read == 0) {
                        continue;
                    }
                    String data = new String(conn.readBuf, 0, read, StandardCharsets.ISO_8859_1);
                    List<NetMessage> decoded = conn.transport.decodeIncoming(0, "", "", data);
                    if (!decoded.isEmpty()) {
                        synchronized (conn) {
                            conn.pendingMessages.addAll(decoded);
                        }
                    }
                } catch (IOException e) {
                    if (isCurrentConnection(instanceId, conn)) {
                        queueTerminal(instanceId, conn, -2, "read failed: " + e.getMessage());
                    }
                    return;
                }
            }
        }, "MUS-read-" + instanceId);
        t.setDaemon(true);
        t.start();
    }

    private void writeOrQueueTerminal(int instanceId, Connection conn, byte[] raw) {
        try {
            conn.out.write(raw);
            conn.out.flush();
        } catch (IOException e) {
            queueTerminal(instanceId, conn, -2, "send failed: " + e.getMessage());
        }
    }

    private static List<NetMessage> drainPendingMessages(Connection conn) {
        synchronized (conn) {
            if (conn.pendingMessages.isEmpty()) {
                return List.of();
            }
            List<NetMessage> out = new ArrayList<>(conn.pendingMessages);
            conn.pendingMessages.clear();
            return out;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
