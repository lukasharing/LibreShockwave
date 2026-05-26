package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Bridge interface for Multiuser Xtra network transport.
 * Platform-specific implementations handle the actual network I/O.
 * For WASM: queue-based bridge where JS handles WebSocket communication.
 */
public interface MultiuserNetBridge {

    /**
     * A received network message.
     */
    record NetMessage(int errorCode, String senderID, String subject, Datum content) {}

    /**
     * Request a connection to a server.
     * The bridge implementation decides when/how to actually connect.
     */
    void requestConnect(int instanceId, String host, int port);

    /**
     * Request a connection to a server with the authored connectToNetServer
     * mode flag. Director passes this flag through the Multiuser Xtra; platform
     * bridges may use it to select the compatible wire representation.
     */
    default void requestConnect(int instanceId, String host, int port, int modeFlag) {
        requestConnect(instanceId, host, port);
    }

    /**
     * Send a message to the server.
     * @param senderID sender identifier ("*" for default)
     * @param subject message subject
     * @param content message content (string or raw data)
     */
    void requestSend(int instanceId, String senderID, String subject, Datum content);

    /**
     * Request disconnection.
     */
    void requestDisconnect(int instanceId);

    /**
     * Check if the connection is established.
     */
    boolean isConnected(int instanceId);

    /**
     * Poll for received messages. Returns and removes pending messages.
     */
    List<NetMessage> pollMessages(int instanceId);

    /**
     * Called when an instance is destroyed (cleanup).
     */
    void destroyInstance(int instanceId);
}
