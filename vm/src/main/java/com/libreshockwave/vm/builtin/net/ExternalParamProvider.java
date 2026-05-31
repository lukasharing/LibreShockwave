package com.libreshockwave.vm.builtin.net;

import java.util.Map;

/**
 * Interface for external parameter access.
 * Provides Shockwave-style externalParamValue/Name/Count builtins.
 * These correspond to PARAM tags in the HTML page that embeds the Shockwave plugin.
 */
public interface ExternalParamProvider {

    /**
     * Get the value of an external parameter by name (case-insensitive).
     * @param name The parameter name (e.g. "sw1")
     * @return The value, or null if not found
     */
    String getParamValue(String name);

    /**
     * Get the name of an external parameter by 1-based index.
     * @param index 1-based index
     * @return The name, or null if out of range
     */
    String getParamName(int index);

    /**
     * Get the total number of external parameters.
     */
    int getParamCount();

    /**
     * Get all external parameters as a map.
     */
    Map<String, String> getAllParams();

    /**
     * Resolve a launch variable parsed from Shockwave swN parameter payloads.
     * This is host/embed configuration, not Director member lookup state.
     */
    default String getLaunchVariable(String name) {
        return null;
    }

    final class Holder {
        private Holder() {}
        static ExternalParamProvider current;
    }

    static void setProvider(ExternalParamProvider provider) {
        Holder.current = provider;
    }

    static void clearProvider() {
        Holder.current = null;
    }

    static ExternalParamProvider getProvider() {
        return Holder.current;
    }
}
