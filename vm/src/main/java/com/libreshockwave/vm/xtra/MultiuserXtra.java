package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.datum.Datum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Director Multiuser Xtra.
 * Provides network messaging for Lingo scripts through Director's Multiuser Xtra API.
 *
 * Lingo usage:
 *   pXtra = new(xtra("Multiuser"))
 *   pXtra.setNetBufferLimits(16384, 102400, 100)
 *   pXtra.setNetMessageHandler(#xtraMsgHandler, me)
 *   pXtra.connectToNetServer("*", "*", host, port, "*", 0)
 *   pXtra.sendNetMessage("*", subject, content)
 *   pXtra.checkNetMessages(1)  -- fires callback
 *   tMsg = pXtra.getNetMessage()  -- inside callback
 */
public class MultiuserXtra implements Xtra {

    private static final int TICK_CALLBACK_BURST_LIMIT = 32;

    private final MultiuserNetBridge netBridge;
    private final ScriptCallback scriptCallback;
    private final Map<Integer, InstanceState> instances = new HashMap<>();
    private int nextInstanceId = 1;

    public MultiuserXtra(MultiuserNetBridge netBridge, ScriptCallback scriptCallback) {
        this.netBridge = netBridge;
        this.scriptCallback = scriptCallback;
    }

    @Override
    public String getName() {
        return "Multiuser";
    }

    @Override
    public int createInstance(List<Datum> args) {
        int id = nextInstanceId++;
        instances.put(id, new InstanceState());
        return id;
    }

    @Override
    public void destroyInstance(int instanceId) {
        InstanceState state = instances.remove(instanceId);
        if (state != null) {
            netBridge.requestDisconnect(instanceId);
            netBridge.destroyInstance(instanceId);
        }
    }

    @Override
    public Datum callHandler(int instanceId, String handlerName, List<Datum> args) {
        InstanceState state = instances.get(instanceId);
        if (state == null) {
            System.err.println("[MultiuserXtra] Instance not found: " + instanceId);
            return Datum.VOID;
        }

        return switch (normalizeHandlerName(handlerName)) {
            case "setnetbufferlimits" -> setNetBufferLimits(state, args);
            case "setnetmessagehandler" -> setNetMessageHandlerOrBufferLimits(state, args);
            case "connecttonetserver" -> connectOrSetMessageHandler(instanceId, state, args);
            case "sendnetmessage" -> sendNetMessageOrBufferLimits(instanceId, state, args);
            case "getnetmessage" -> getNetMessage(state);
            case "checknetmessages" -> checkNetMessages(instanceId, state, args);
            case "breakconnection" -> breakConnection(instanceId, state);
            case "getpeerconnectionlist" -> Datum.list();
            case "getnetaddresscookie" -> Datum.EMPTY_STRING;
            case "getnetoutgoingbytes" -> Datum.ZERO;
            case "getnumberwaitingnetmessages" -> getNumberWaitingNetMessages(instanceId, state);
            case "waitfornetconnection" -> Datum.of(-1);
            case "getneterrorstring" -> getNetErrorString(args);
            default -> resolveProtectedSelectorBySignature(instanceId, state, handlerName, args);
        };
    }

    @Override
    public Datum getProperty(int instanceId, String propertyName) {
        return Datum.VOID;
    }

    @Override
    public void setProperty(int instanceId, String propertyName, Datum value) {
    }

    /**
     * Stage Multiuser socket messages during the normal Director tick.
     *
     * Movies that only install a net message handler receive callbacks from the
     * tick pump. Once a movie starts using the explicit waiting/check API on an
     * instance, callback delivery is driven by authored checkNetMessages calls.
     */
    @Override
    public void tick() {
        for (var entry : instances.entrySet()) {
            int instanceId = entry.getKey();
            InstanceState state = entry.getValue();

            if (state.callbackHandler == null || state.callbackTarget == null) {
                continue;
            }

            int received = pollBridgeMessages(instanceId, state, "tick");
            if (received > 0 || !state.messageQueue.isEmpty()) {
                debug("tick staged instance=" + instanceId
                        + " received=" + received
                        + " queued=" + state.messageQueue.size());
            }
            if (!state.explicitPolling) {
                int callbackBudget = Math.min(state.messageQueue.size(), TICK_CALLBACK_BURST_LIMIT);
                drainCallbacks(instanceId, state, callbackBudget, "tick");
            }
        }
    }

    // --- Handler implementations ---

    private Datum setNetBufferLimits(InstanceState state, List<Datum> args) {
        if (args.size() >= 3) {
            state.bufferMin = args.get(0).toInt();
            state.bufferMax = args.get(1).toInt();
            state.bufferUrgency = args.get(2).toInt();
        }
        return Datum.ZERO;
    }

    private Datum setNetMessageHandlerOrBufferLimits(InstanceState state, List<Datum> args) {
        if (looksLikeBufferLimits(args)) {
            return setNetBufferLimits(state, args);
        }
        return setNetMessageHandler(state, args);
    }

    private Datum setNetMessageHandler(InstanceState state, List<Datum> args) {
        if (args.size() >= 2) {
            Datum handlerArg = args.get(0);
            Datum targetArg = args.get(1);

            if (handlerArg.isVoid() || targetArg.isVoid()) {
                // Director lets movies clear the callback without closing the
                // socket. Connection lifetime is controlled by the Xtra instance
                // or explicit disconnect/destroy paths, not by handler routing.
                state.callbackHandler = null;
                state.callbackTarget = null;
                debug("handler cleared");
            } else {
                state.callbackHandler = handlerArg instanceof Datum.Symbol sym
                        ? sym.name() : handlerArg.toStr();
                state.callbackTarget = targetArg;
                debug("handler set handler=" + state.callbackHandler
                        + " target=" + preview(targetArg));
            }
        }
        return Datum.ZERO; // 0 = success
    }

    private Datum connectOrSetMessageHandler(int instanceId, InstanceState state, List<Datum> args) {
        if (args.size() == 2) {
            return setNetMessageHandler(state, args);
        }
        return connectToNetServer(instanceId, state, args);
    }

    private Datum connectToNetServer(int instanceId, InstanceState state, List<Datum> args) {
        // connectToNetServer(senderID, user, host, port, appID, encryptFlag)
        if (args.size() >= 4) {
            String host = args.get(2).toStr();
            int port = args.get(3).toInt();
            int modeFlag = args.size() >= 6 ? args.get(5).toInt() : 0;
            state.host = host;
            state.port = port;
            state.currentMessage = null;
            state.messageQueue.clear();
            state.explicitPolling = false;
            debug("connect instance=" + instanceId
                    + " host=" + host
                    + " port=" + port
                    + " mode=" + modeFlag);
            netBridge.requestConnect(instanceId, host, port, modeFlag);
        }
        return Datum.ZERO;
    }

    private Datum sendNetMessageOrBufferLimits(int instanceId, InstanceState state, List<Datum> args) {
        if (looksLikeBufferLimits(args)) {
            return setNetBufferLimits(state, args);
        }
        return sendNetMessage(instanceId, state, args);
    }

    private Datum sendNetMessage(int instanceId, InstanceState state, List<Datum> args) {
        // sendNetMessage(senderID, subject, content)
        if (args.size() >= 3) {
            String senderID = args.get(0).toStr();
            String subject = args.get(1).toStr();
            Datum content = args.get(2);
            debug("send instance=" + instanceId
                    + " sender=" + senderID
                    + " subject=" + subject
                    + " content=" + contentSummary(content));
            netBridge.requestSend(instanceId, senderID, subject, content);
        }
        return Datum.ZERO;
    }

    private Datum getNetMessage(InstanceState state) {
        if (state.currentMessage == null) {
            return Datum.VOID;
        }

        MultiuserNetBridge.NetMessage msg = state.currentMessage;
        Datum.PropList pl = new Datum.PropList();
        pl.add("errorCode", Datum.of(msg.errorCode()), true);
        pl.add("senderID", Datum.of(msg.senderID()), true);
        pl.add("subject", Datum.of(msg.subject()), true);
        pl.add("content", msg.content() != null ? msg.content() : Datum.VOID, true);
        pl.add("recipients", toDatumList(msg.recipients()), true);
        pl.add("timeStamp", Datum.of(msg.timeStamp()), true);
        // Protected casts can call the same native Xtra entrypoints through
        // renamed selectors/properties. Keep the public Director keys and
        // expose the selector-local aliases against the same message.
        pl.add("Crypto_DecryptHeader", Datum.of(msg.errorCode()), true);
        pl.add("txtColor", Datum.of(msg.errorCode()), true);
        pl.add("tSubject", Datum.of(msg.subject()), true);
        pl.add("strechV", Datum.of(msg.subject()), true);
        pl.add("systemMac", msg.content() != null ? msg.content() : Datum.VOID, true);
        return pl;
    }

    private Datum checkNetMessages(int instanceId, InstanceState state, List<Datum> args) {
        int count = args.isEmpty() ? 1 : args.get(0).toInt();
        state.explicitPolling = true;
        int received = pollBridgeMessages(instanceId, state, "check");
        if (received > 0 || !state.messageQueue.isEmpty()) {
            debug("check instance=" + instanceId
                    + " requested=" + count
                    + " received=" + received
                    + " queued=" + state.messageQueue.size()
                    + " handler=" + state.callbackHandler);
        }

        return Datum.of(drainCallbacks(instanceId, state, count, "check"));
    }

    private Datum breakConnection(int instanceId, InstanceState state) {
        state.currentMessage = null;
        state.messageQueue.clear();
        netBridge.requestDisconnect(instanceId);
        return Datum.ZERO;
    }

    private int drainCallbacks(int instanceId, InstanceState state, int count, String mode) {
        int processed = 0;
        for (int i = 0; i < count && !state.messageQueue.isEmpty(); i++) {
            state.currentMessage = state.messageQueue.remove(0);
            processed++;

            // Fire the registered callback
            if (state.callbackHandler != null && state.callbackTarget != null) {
                try {
                    traceCallback(instanceId, state, mode);
                    scriptCallback.invoke(state.callbackTarget, state.callbackHandler, List.of());
                } catch (Exception e) {
                    System.err.println("[MultiuserXtra] Callback error"
                            + " instance=" + instanceId
                            + " handler=" + state.callbackHandler
                            + " message=" + contentSummary(state.currentMessage != null
                                    ? state.currentMessage.content() : Datum.VOID)
                            + ": " + e.getMessage());
                    if (DebugConfig.isMusTraceEnabled() || DebugConfig.isDebugPlaybackEnabled()) {
                        e.printStackTrace(System.err);
                    }
                }
            }
        }

        state.currentMessage = null;
        return processed;
    }

    private Datum getNumberWaitingNetMessages(int instanceId, InstanceState state) {
        state.explicitPolling = true;
        int received = pollBridgeMessages(instanceId, state, "waiting");
        if (received > 0) {
            debug("waiting instance=" + instanceId
                    + " received=" + received
                    + " queued=" + state.messageQueue.size());
        }
        return Datum.of(state.messageQueue.size());
    }

    private int pollBridgeMessages(int instanceId, InstanceState state, String mode) {
        List<MultiuserNetBridge.NetMessage> messages = netBridge.pollMessages(instanceId);
        state.messageQueue.addAll(messages);
        if (!messages.isEmpty()) {
            debug("poll mode=" + mode
                    + " instance=" + instanceId
                    + " received=" + messages.size()
                    + " queued=" + state.messageQueue.size()
                    + " handler=" + state.callbackHandler);
        }
        return messages.size();
    }

    private Datum resolveProtectedSelectorBySignature(
            int instanceId, InstanceState state, String handlerName, List<Datum> args) {
        // Some protected Director casts call native Xtra methods through symbol
        // names that are not the public handler names. The native projector
        // still resolves those calls through the Xtra method table. Until the
        // VM preserves that selector identity, keep this resolution strictly
        // signature-based and local to the Multiuser API.
        if (looksLikeBufferLimits(args)) {
            return setNetBufferLimits(state, args);
        }
        if (looksLikeMessageHandler(args)) {
            return setNetMessageHandler(state, args);
        }
        if (looksLikeConnect(args)) {
            return connectToNetServer(instanceId, state, args);
        }
        if (looksLikeSend(args)) {
            return sendNetMessage(instanceId, state, args);
        }
        if (args.size() == 1 && args.get(0).isNumber()) {
            return checkNetMessages(instanceId, state, args);
        }
        if (args.isEmpty()) {
            return state.currentMessage != null
                    ? getNetMessage(state)
                    : getNumberWaitingNetMessages(instanceId, state);
        }

        System.err.println("[MultiuserXtra] Unknown handler: " + handlerName);
        return Datum.VOID;
    }

    private static Datum toDatumList(List<String> values) {
        List<Datum> items = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                items.add(Datum.of(value != null ? value : ""));
            }
        }
        return Datum.list(items);
    }

    private Datum getNetErrorString(List<Datum> args) {
        if (args.isEmpty()) return Datum.of("");
        int code = args.get(0).toInt();
        return Datum.of(switch (code) {
            case 0 -> "No error";
            case -1 -> "Memory allocation error";
            case -2 -> "Network error";
            case -3 -> "Connection refused";
            case -4 -> "Connection timed out";
            case -5 -> "Invalid message";
            case -6 -> "Invalid server address";
            default -> "Unknown error (" + code + ")";
        });
    }

    private void traceCallback(int instanceId, InstanceState state, String mode) {
        MultiuserNetBridge.NetMessage message = state.currentMessage;
        if (message == null) {
            return;
        }
        debug("callback mode=" + mode
                + " instance=" + instanceId
                + " handler=" + state.callbackHandler
                + " error=" + message.errorCode()
                + " sender=" + message.senderID()
                + " subject=" + message.subject()
                + " content=" + contentSummary(message.content()));
    }

    private static void debug(String message) {
        if (DebugConfig.isDebugPlaybackEnabled() || DebugConfig.isMusTraceEnabled()) {
            System.out.println("[MultiuserXtra] " + message);
        }
    }

    private static String normalizeHandlerName(String handlerName) {
        if (handlerName == null) {
            return "";
        }
        String normalized = handlerName.toLowerCase();
        return normalized.startsWith("#") ? normalized.substring(1) : normalized;
    }

    private static boolean looksLikeBufferLimits(List<Datum> args) {
        return args.size() >= 3
                && args.get(0).isNumber()
                && args.get(1).isNumber()
                && args.get(2).isNumber()
                && args.get(0).toInt() > 0
                && args.get(1).toInt() > 0;
    }

    private static boolean looksLikeMessageHandler(List<Datum> args) {
        if (args.size() != 2) {
            return false;
        }
        Datum handler = args.get(0);
        return handler.isVoid()
                || handler instanceof Datum.Symbol
                || handler instanceof Datum.Str;
    }

    private static boolean looksLikeConnect(List<Datum> args) {
        return args.size() >= 4
                && !args.get(2).isVoid()
                && !args.get(2).isNumber()
                && args.get(3).isNumber();
    }

    private static boolean looksLikeSend(List<Datum> args) {
        return args.size() >= 3;
    }

    private static String preview(Datum datum) {
        if (datum == null || datum.isVoid()) {
            return "VOID";
        }
        String raw = datum.toStr();
        StringBuilder out = new StringBuilder();
        int limit = Math.min(raw.length(), 80);
        for (int i = 0; i < limit; i++) {
            char ch = raw.charAt(i);
            if (ch < 32 || ch == 127) {
                out.append("\\x");
                String hex = Integer.toHexString(ch).toUpperCase();
                if (hex.length() == 1) {
                    out.append('0');
                }
                out.append(hex);
            } else {
                out.append(ch);
            }
        }
        if (raw.length() > limit) {
            out.append("...");
        }
        return '"' + out.toString() + '"';
    }

    private static String contentSummary(Datum datum) {
        if (datum == null || datum.isVoid()) {
            return "VOID";
        }
        return "len=" + datum.toStr().length();
    }

    // --- Instance state ---

    private static class InstanceState {
        String host;
        int port;
        int bufferMin;
        int bufferMax;
        int bufferUrgency;
        String callbackHandler;
        Datum callbackTarget;
        boolean explicitPolling;
        MultiuserNetBridge.NetMessage currentMessage;
        final List<MultiuserNetBridge.NetMessage> messageQueue = new ArrayList<>();
    }
}
