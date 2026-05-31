package com.libreshockwave.vm.builtin.sprite;

import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Shared constants and hooks for synthetic sprite event brokers.
 *
 * Authored broker behavior still lives in scriptInstanceList. This class keeps
 * the VM layer decoupled from player-core while preserving the marker used when
 * sprite channels are rebound.
 */
public final class SpriteEventBrokerSupport {
    public static final String SYNTHETIC_BROKER_FLAG = "__syntheticSpriteEventBroker";

    private SpriteEventBrokerSupport() {}

    public static Datum dispatchSpriteMethod(int channel, String handlerName, List<Datum> args) {
        return Datum.VOID;
    }
}
