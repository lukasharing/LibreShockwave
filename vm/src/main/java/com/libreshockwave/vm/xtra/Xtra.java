package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Interface for Xtra implementations.
 * Xtras are plugin extensions in Director/Shockwave.
 * Similar to dirplayer-rs player/xtra/mod.rs.
 */
public interface Xtra {

    /**
     * Get the name of this Xtra.
     */
    String getName();

    /**
     * Create a new instance of this Xtra.
     * @param args Constructor arguments
     * @return Instance ID for tracking
     */
    int createInstance(List<Datum> args);

    /**
     * Destroy an instance of this Xtra.
     */
    void destroyInstance(int instanceId);

    /**
     * Call a handler on an Xtra instance.
     * @param instanceId The instance ID
     * @param handlerName The handler name
     * @param args Handler arguments
     * @return The result
     */
    Datum callHandler(int instanceId, String handlerName, List<Datum> args);

    /**
     * Resolve a handler name for Xtras that publish an explicit Director
     * msgTable. Protected movies can carry multiple Lnam tables; if the current
     * script resolves an OBJ_CALL selector through the wrong table, the same
     * numeric selector may still resolve to the public Xtra method name in
     * another loaded table. Xtras with a known msgTable can opt in here.
     */
    default String resolveHandlerName(int instanceId, String handlerName, List<String> candidateNames) {
        return handlerName;
    }

    /**
     * Get a property from an Xtra instance.
     */
    Datum getProperty(int instanceId, String propertyName);

    /**
     * Set a property on an Xtra instance.
     */
    void setProperty(int instanceId, String propertyName, Datum value);

    /**
     * Called each frame to process pending async operations (e.g., network callbacks).
     * Default implementation does nothing.
     */
    default void tick() {}
}
