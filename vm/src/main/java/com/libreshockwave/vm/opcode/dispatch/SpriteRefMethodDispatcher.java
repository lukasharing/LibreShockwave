package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.sprite.SpritePropertyProvider;
import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Handles built-in methods exposed by Director sprite references.
 *
 * This intentionally covers only native sprite-object methods. Event-broker
 * APIs such as setID/registerProcedure must come from authored behavior
 * instances attached to the sprite, not from a synthetic Java broker.
 */
public final class SpriteRefMethodDispatcher {

    private SpriteRefMethodDispatcher() {}

    public static Datum dispatch(int channel, String methodName, List<Datum> args) {
        SpritePropertyProvider provider = SpritePropertyProvider.getProvider();
        if (provider == null || channel <= 0 || methodName == null) {
            return Datum.VOID;
        }

        String method = LingoVM.normalizeLookupName(methodName);
        return switch (method) {
            case "setmember" -> setMember(provider, channel, args);
            case "getmember" -> provider.getSpriteProp(channel, "member");
            case "setcursor" -> setCursor(provider, channel, args);
            case "getcursor" -> provider.getSpriteProp(channel, "cursor");
            default -> Datum.VOID;
        };
    }

    private static Datum setMember(SpritePropertyProvider provider, int channel, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.ZERO;
        }
        return provider.setSpriteMember(channel, args.get(0)) ? Datum.TRUE : Datum.ZERO;
    }

    private static Datum setCursor(SpritePropertyProvider provider, int channel, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.ZERO;
        }
        return provider.setSpriteProp(channel, "cursor", args.get(0)) ? Datum.TRUE : Datum.ZERO;
    }
}
