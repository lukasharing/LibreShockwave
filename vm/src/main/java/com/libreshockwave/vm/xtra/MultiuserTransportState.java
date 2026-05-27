package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection wire-format state for Director Multiuser transports.
 *
 * Some legacy movies open a connection through the Multiuser Xtra but then use
 * an authored protocol implementation that sends raw payloads through the
 * content field. Keep the protocol decision at the transport boundary so
 * player bridges do not need movie-specific command knowledge.
 */
public final class MultiuserTransportState {

    private boolean contentOnly;
    private String smusInboundBuffer = "";

    public MultiuserTransportState(int modeFlag) {
        contentOnly = modeFlag != 0;
    }

    public String encodeOutgoing(String senderID, String subject, Datum content) {
        String contentString = content != null ? content.toStr() : "";
        if (willSendContentOnly(senderID, subject)) {
            return contentString;
        }
        return MultiuserTransportCodec.encodeSmusPacket(senderID, subject, contentString);
    }

    public boolean willSendContentOnly(String senderID, String subject) {
        return contentOnly || MultiuserTransportCodec.isContentOnlyEnvelope(senderID, subject);
    }

    public List<MultiuserNetBridge.NetMessage> decodeIncoming(
            int errorCode, String senderID, String subject, String content) {
        if (!contentOnly && (subject == null || subject.isEmpty())) {
            return decodeNominalSmus(errorCode, senderID, subject, content);
        }
        return List.of(rawMessage(errorCode, senderID, subject, content));
    }

    public boolean isContentOnly() {
        return contentOnly;
    }

    private List<MultiuserNetBridge.NetMessage> decodeNominalSmus(
            int errorCode, String senderID, String subject, String content) {
        String incoming = content != null ? content : "";
        String combined = smusInboundBuffer + incoming;
        if (!MultiuserTransportCodec.couldStartSmusPacket(combined)) {
            smusInboundBuffer = "";
            contentOnly = true;
            return List.of(rawMessage(errorCode, senderID, subject, combined));
        }

        MultiuserTransportCodec.SmusParseResult result =
                MultiuserTransportCodec.parseSmusPackets(combined);
        if (result.messages().isEmpty()) {
            smusInboundBuffer = combined;
            return List.of();
        }

        List<MultiuserNetBridge.NetMessage> out = new ArrayList<>();
        for (MultiuserTransportCodec.SmusMessage msg : result.messages()) {
            out.add(new MultiuserNetBridge.NetMessage(
                    msg.errorCode(), msg.senderID(), msg.subject(), new Datum.Str(msg.content())));
        }

        String remainder = result.consumedChars() < combined.length()
                ? combined.substring(result.consumedChars())
                : "";
        if (!remainder.isEmpty() && !MultiuserTransportCodec.couldStartSmusPacket(remainder)) {
            smusInboundBuffer = "";
            contentOnly = true;
            out.add(rawMessage(errorCode, senderID, subject, remainder));
            return out;
        }

        smusInboundBuffer = remainder;
        return out;
    }

    private static MultiuserNetBridge.NetMessage rawMessage(
            int errorCode, String senderID, String subject, String content) {
        return new MultiuserNetBridge.NetMessage(
                errorCode,
                senderID != null ? senderID : "",
                subject != null ? subject : "",
                new Datum.Str(content != null ? content : ""));
    }
}
