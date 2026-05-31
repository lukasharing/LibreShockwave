package com.libreshockwave.player.wasm;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.xtra.MultiuserTransportState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queue-based MultiuserNetBridge for WASM.
 * Java queues requests (connect/send/disconnect); JS polls them each tick.
 * JS delivers events (connected/message/disconnected) back via WasmEntry exports.
 */
public class WasmMultiuserBridge implements MultiuserNetBridge {

    // --- Pending requests (Java → JS) ---

    static final int REQ_CONNECT    = 0;
    static final int REQ_SEND       = 1;
    static final int REQ_DISCONNECT = 2;

    static class PendingRequest {
        final int type;
        final int instanceId;
        String host;
        int port;
        String senderID;
        String subject;
        String content;

        PendingRequest(int type, int instanceId) {
            this.type = type;
            this.instanceId = instanceId;
        }
    }

    private final List<PendingRequest> pendingRequests = new ArrayList<>();
    private final Map<Integer, Boolean> connectedMap = new HashMap<>();
    private final Map<Integer, List<NetMessage>> messageQueues = new HashMap<>();
    private final Set<Integer> closingInstances = new HashSet<>();
    private final Set<Integer> terminalInstances = new HashSet<>();
    private final Map<Integer, MultiuserTransportState> transports = new HashMap<>();

    // --- MultiuserNetBridge implementation ---

    @Override
    public void requestConnect(int instanceId, String host, int port) {
        requestConnect(instanceId, host, port, 0);
    }

    @Override
    public void requestConnect(int instanceId, String host, int port, int modeFlag) {
        debug("request connect instance=" + instanceId + " host=" + host + " port=" + port
                + " mode=" + modeFlag + (modeFlag != 0 ? " content-only" : " smus"));
        closingInstances.remove(instanceId);
        terminalInstances.remove(instanceId);
        connectedMap.remove(instanceId);
        messageQueues.remove(instanceId);
        transports.put(instanceId, new MultiuserTransportState(modeFlag));
        PendingRequest req = new PendingRequest(REQ_CONNECT, instanceId);
        req.host = host;
        req.port = port;
        pendingRequests.add(req);
    }

    @Override
    public void requestSend(int instanceId, String senderID, String subject, Datum content) {
        String contentString = content.toStr();
        if (terminalInstances.contains(instanceId)) {
            debug("request send ignored for terminal instance=" + instanceId
                    + " sender=" + senderID + " subject=" + subject
                    + " bytes=" + contentString.length());
            return;
        }
        MultiuserTransportState transport =
                transports.computeIfAbsent(instanceId, ignored -> new MultiuserTransportState(0));
        boolean contentOnly = transport.willSendContentOnly(senderID, subject);
        String encoded = transport.encodeOutgoing(senderID, subject, content);
        debug("request send instance=" + instanceId + " sender=" + senderID
                + " subject=" + subject + " contentOnly=" + contentOnly
                + " bytes=" + contentString.length() + " content=" + preview(contentString));
        PendingRequest req = new PendingRequest(REQ_SEND, instanceId);
        req.senderID = senderID;
        req.subject = subject;
        req.content = encoded;
        pendingRequests.add(req);
    }

    @Override
    public void requestDisconnect(int instanceId) {
        debug("request disconnect instance=" + instanceId);
        closingInstances.add(instanceId);
        terminalInstances.add(instanceId);
        messageQueues.remove(instanceId);
        PendingRequest req = new PendingRequest(REQ_DISCONNECT, instanceId);
        pendingRequests.add(req);
        connectedMap.remove(instanceId);
        transports.remove(instanceId);
    }

    @Override
    public boolean isConnected(int instanceId) {
        return Boolean.TRUE.equals(connectedMap.get(instanceId));
    }

    @Override
    public List<NetMessage> pollMessages(int instanceId) {
        List<NetMessage> queue = messageQueues.remove(instanceId);
        return queue != null ? queue : List.of();
    }

    @Override
    public void destroyInstance(int instanceId) {
        debug("destroy instance=" + instanceId);
        closingInstances.add(instanceId);
        terminalInstances.add(instanceId);
        connectedMap.remove(instanceId);
        messageQueues.remove(instanceId);
        transports.remove(instanceId);
    }

    // --- JS polling API ---

    List<PendingRequest> getPendingRequests() {
        return pendingRequests;
    }

    PendingRequest getRequest(int index) {
        return index >= 0 && index < pendingRequests.size() ? pendingRequests.get(index) : null;
    }

    void drainPendingRequests() {
        pendingRequests.clear();
    }

    // --- JS delivery API ---

    void notifyConnected(int instanceId) {
        closingInstances.remove(instanceId);
        terminalInstances.remove(instanceId);
        connectedMap.put(instanceId, true);
        debug("connected instance=" + instanceId);
        // Director's Multiuser Xtra reports a successful connection with this
        // system message before authored scripts begin application traffic.
        queueMessage(instanceId, new NetMessage(0, "System", "ConnectToNetServer", new Datum.Str("")));
    }

    void notifyDisconnected(int instanceId) {
        notifyDisconnected(instanceId, 0, false, "");
    }

    void notifyDisconnected(int instanceId, int closeCode, boolean wasClean, String detail) {
        connectedMap.remove(instanceId);
        if (closingInstances.contains(instanceId)) {
            terminalInstances.add(instanceId);
            transports.remove(instanceId);
            debug("disconnected ignored for closing instance=" + instanceId
                    + formatCloseDetail(closeCode, wasClean, detail));
            return;
        }
        String diagnostic = formatCloseDetail(closeCode, wasClean, detail);
        terminalInstances.add(instanceId);
        transports.remove(instanceId);
        debug("disconnected instance=" + instanceId + diagnostic);
        queueMessage(instanceId, new NetMessage(-2, "System", "ConnectionProblem", new Datum.Str(diagnostic.trim())));
    }

    void notifyError(int instanceId, int errorCode) {
        notifyError(instanceId, errorCode, "");
    }

    void notifyError(int instanceId, int errorCode, String detail) {
        connectedMap.remove(instanceId);
        terminalInstances.add(instanceId);
        transports.remove(instanceId);
        if (closingInstances.contains(instanceId)) {
            debug("error ignored for closing instance=" + instanceId + " code=" + errorCode
                    + formatTextDetail(detail));
            return;
        }
        String diagnostic = formatTextDetail(detail);
        debug("error instance=" + instanceId + " code=" + errorCode + diagnostic);
        queueMessage(instanceId, new NetMessage(errorCode, "System", "ConnectionProblem", new Datum.Str(diagnostic.trim())));
    }

    void deliverMessage(int instanceId, int errorCode, String senderID, String subject, String content) {
        MultiuserTransportState transport = transports.get(instanceId);
        if (transport == null) {
            debug("message instance=" + instanceId + " bytes=" + (content != null ? content.length() : 0)
                    + " content=" + preview(content));
            queueMessage(instanceId, new NetMessage(errorCode, senderID, subject, new Datum.Str(content)));
            return;
        }
        boolean wasContentOnly = transport.isContentOnly();
        List<NetMessage> decoded = transport.decodeIncoming(errorCode, senderID, subject, content);
        if (decoded.isEmpty()) {
            debug("message instance=" + instanceId + " buffered=" + (content != null ? content.length() : 0));
            return;
        }
        if (!wasContentOnly && transport.isContentOnly()) {
            debug("message instance=" + instanceId + " switching-to-content-only");
        }
        for (NetMessage msg : decoded) {
            debug("message instance=" + instanceId + " subject=" + msg.subject()
                    + " content=" + preview(msg.content() != null ? msg.content().toStr() : null));
            queueMessage(instanceId, msg);
        }
    }

    private void queueMessage(int instanceId, NetMessage msg) {
        debug("queue instance=" + instanceId + " error=" + msg.errorCode()
                + " subject=" + msg.subject() + " content=" + preview(msg.content() != null ? msg.content().toStr() : null));
        messageQueues.computeIfAbsent(instanceId, k -> new ArrayList<>()).add(msg);
    }

    private static void debug(String message) {
        if (DebugConfig.isDebugPlaybackEnabled()) {
            System.out.println("[MUSBridge] " + message);
        }
    }

    private static String formatCloseDetail(int closeCode, boolean wasClean, String detail) {
        StringBuilder out = new StringBuilder();
        if (closeCode != 0) {
            out.append(" closeCode=").append(closeCode);
        }
        out.append(" wasClean=").append(wasClean);
        appendDetail(out, detail);
        return out.toString();
    }

    private static String formatTextDetail(String detail) {
        StringBuilder out = new StringBuilder();
        appendDetail(out, detail);
        return out.toString();
    }

    private static void appendDetail(StringBuilder out, String detail) {
        if (detail == null || detail.isBlank()) {
            return;
        }
        out.append(" detail=").append(preview(detail));
    }

    private static String preview(String content) {
        if (content == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        int limit = Math.min(content.length(), 32);
        for (int i = 0; i < limit; i++) {
            char c = content.charAt(i);
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append("\\x");
                String hex = Integer.toHexString(c & 0xff).toUpperCase();
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
        }
        if (content.length() > limit) sb.append("...");
        sb.append('"');
        return sb.toString();
    }
}
