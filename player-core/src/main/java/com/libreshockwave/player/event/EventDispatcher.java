package com.libreshockwave.player.event;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.PlayerEvent;
import com.libreshockwave.player.behavior.BehaviorInstance;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.flow.ControlFlowBuiltins;
import com.libreshockwave.vm.util.AncestorChainWalker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dispatches events to scripts in the correct order.
 * Follows Director's event propagation: sprite behaviors → frame behaviors → movie scripts.
 */
public class EventDispatcher {
    private final DirectorFile file;
    private final LingoVM vm;
    private final BehaviorManager behaviorManager;
    private CastLibManager castLibManager;
    private SpriteRegistry spriteRegistry;

    // Debug logging
    private boolean debugEnabled = false;

    // Event propagation control
    // In Director: if a handler exists and doesn't call pass(), propagation STOPS
    // If pass() is called, propagation CONTINUES to next handler
    private boolean stopPropagation = false;

    public EventDispatcher(DirectorFile file, LingoVM vm, BehaviorManager behaviorManager) {
        this.file = file;
        this.vm = vm;
        this.behaviorManager = behaviorManager;

        // Register pass callback with VM
        vm.setPassCallback(this::pass);
    }

    public void setCastLibManager(CastLibManager castLibManager) {
        this.castLibManager = castLibManager;
    }

    public void setSpriteRegistry(SpriteRegistry spriteRegistry) {
        this.spriteRegistry = spriteRegistry;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    /**
     * Dispatch a global event to all scripts.
     * Order: sprite behaviors → frame behavior → movie scripts
     */
    public void dispatchGlobalEvent(PlayerEvent event, List<Datum> args) {
        dispatchGlobalEvent(event.getHandlerName(), args);
    }

    /**
     * Dispatch a global event by handler name.
     */
    public void dispatchGlobalEvent(String handlerName, List<Datum> args) {
        stopPropagation = false;
        // Reset error state at start of each event dispatch
        // This allows execution to continue after errors
        vm.resetErrorState();

        // Director event propagation model:
        // - Each sprite's behaviors form their own propagation chain.
        //   stopPropagation within a sprite only prevents OTHER behaviors
        //   on the SAME sprite from receiving the event.
        // - Frame script and movie scripts are dispatched independently
        //   of sprite behavior propagation.

        // 1. Sprite behaviors (in channel order, reset propagation per sprite)
        List<BehaviorInstance> spriteInstances = behaviorManager.getSpriteInstances();
        BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
        int lastChannel = -1;
        for (BehaviorInstance instance : spriteInstances) {
            int channel = instance.getSpriteNum();
            if (channel != lastChannel) {
                // New sprite — reset propagation so each sprite gets the event
                stopPropagation = false;
                lastChannel = channel;
            }
            if (stopPropagation) {
                continue;  // Skip remaining behaviors on same sprite
            }
            invokeHandler(instance, handlerName, args);
        }

        // 2. Frame behavior (always dispatched, independent of sprite propagation)
        stopPropagation = false;
        if (frameInstance != null) {
            invokeHandler(frameInstance, handlerName, args);
        }

        // 3. Movie scripts
        if (!stopPropagation) {
            dispatchToMovieScripts(handlerName, args);
        }
    }

    /**
     * Dispatch an event only to frame and movie scripts.
     * Used for frame-level events like enterFrame, exitFrame.
     */
    public void dispatchFrameAndMovieEvent(PlayerEvent event, List<Datum> args) {
        dispatchFrameAndMovieEvent(event.getHandlerName(), args);
    }

    /**
     * Dispatch an event only to frame and movie scripts.
     */
    public void dispatchFrameAndMovieEvent(String handlerName, List<Datum> args) {
        stopPropagation = false;
        vm.resetErrorState();

        // Frame behavior first
        BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
        if (frameInstance != null && behaviorHasHandler(frameInstance, handlerName)) {
            invokeHandler(frameInstance, handlerName, args);
        }

        // Then movie scripts
        if (!stopPropagation) {
            dispatchToMovieScripts(handlerName, args);
        }
    }

    /**
     * Dispatch an event to a specific sprite's behaviors.
     */
    public void dispatchSpriteEvent(int channel, PlayerEvent event, List<Datum> args) {
        dispatchSpriteEvent(channel, event.getHandlerName(), args);
    }

    /**
     * Dispatch an event to a specific sprite's behaviors.
     * Dispatches to both Score-based behaviors (BehaviorManager) and
     * dynamically attached behaviors (sprite.scriptInstanceList).
     */
    public void dispatchSpriteEvent(int channel, String handlerName, List<Datum> args) {
        // Reset error state so stale errors from prior events don't block handlers
        vm.resetErrorState();

        // 1. Score-based behaviors
        List<BehaviorInstance> instances = behaviorManager.getInstancesForChannel(channel);
        for (BehaviorInstance instance : instances) {
            if (!behaviorHasHandler(instance, handlerName)) {
                continue;
            }
            invokeHandler(instance, handlerName, args);
        }

        // 2. Dynamically attached behaviors via scriptInstanceList
        if (spriteRegistry != null) {
            SpriteState sprite = spriteRegistry.get(channel);
            if (sprite != null) {
                List<Datum> scriptInstances = sprite.getScriptInstanceList();
                if (scriptInstances != null && !scriptInstances.isEmpty()) {
                    // Snapshot to avoid ConcurrentModificationException
                    List<Datum> snapshot = new ArrayList<>(scriptInstances);
                    for (Datum target : snapshot) {
                        if (target instanceof Datum.ScriptInstance si) {
                            try {
                                vm.resetErrorState();
                                if (scriptInstanceRespondsToEvent(si, handlerName)) {
                                    traceScriptInstanceInvoke("scriptInstance", handlerName, channel, si);
                                    traceEventBrokerInvoke(handlerName, channel, si);
                                    Datum result = invokeScriptInstanceEvent(si, handlerName, args);
                                    traceScriptInstanceResult("scriptInstance", handlerName, channel, si, result);
                                    traceEventBrokerResult(handlerName, channel, si, result);
                                }
                            } catch (Exception e) {
                                System.err.println("[EventDispatcher] Error in scriptInstanceList handler "
                                        + handlerName + " on sprite " + channel + ": " + e.getMessage());
                                if (debugEnabled) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Check whether a sprite exposes a specific handler through either score-based
     * behaviors or dynamically attached script instances.
     */
    public boolean spriteHasHandler(int channel, String handlerName) {
        for (BehaviorInstance instance : behaviorManager.getInstancesForChannel(channel)) {
            if (behaviorHasHandler(instance, handlerName)) {
                return true;
            }
        }

        if (spriteRegistry != null) {
            SpriteState sprite = spriteRegistry.get(channel);
            if (sprite != null) {
                List<Datum> scriptInstances = sprite.getScriptInstanceList();
                if (scriptInstances != null) {
                    for (Datum target : scriptInstances) {
                        if (target instanceof Datum.ScriptInstance si
                                && scriptInstanceRespondsToEvent(si, handlerName)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Generic mouse-interaction check used by cursor/UI affordances.
     */
    public boolean isSpriteMouseInteractive(int channel) {
        return spriteHasHandler(channel, PlayerEvent.MOUSE_DOWN.getHandlerName())
                || spriteHasHandler(channel, PlayerEvent.MOUSE_UP.getHandlerName())
                || spriteHasHandler(channel, PlayerEvent.MOUSE_ENTER.getHandlerName())
                || spriteHasHandler(channel, PlayerEvent.MOUSE_LEAVE.getHandlerName())
                || spriteHasHandler(channel, PlayerEvent.MOUSE_WITHIN.getHandlerName());
    }

    /**
     * Dispatch an event to movie scripts only using a PlayerEvent constant.
     */
    public void dispatchToMovieScripts(PlayerEvent event, List<Datum> args) {
        dispatchToMovieScripts(event.getHandlerName(), args);
    }

    /**
     * Dispatch an event to movie scripts only.
     * Movie scripts handle movie-level events: prepareMovie, startMovie, stopMovie.
     * Searches the main DCR and all loaded external casts.
     */
    public void dispatchToMovieScripts(String handlerName, List<Datum> args) {
        if (file == null) return;

        // 1. Main DCR movie scripts
        ScriptNamesChunk names = file.getScriptNames();
        if (names != null) {
            dispatchToMovieScriptsIn(file.getScripts(), names, handlerName, args);
        }

        // 2. External cast movie scripts
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                if (!castLib.isExternal() || !castLib.isLoaded()) continue;
                ScriptNamesChunk castNames = castLib.getScriptNames();
                if (castNames == null) continue;
                dispatchToMovieScriptsIn(castLib.getAllScripts(), castNames, handlerName, args);
            }
        }
    }

    /**
     * Dispatch a handler to all movie scripts in a given script collection.
     */
    private void dispatchToMovieScriptsIn(Iterable<ScriptChunk> scripts, ScriptNamesChunk defaultNames,
                                           String handlerName, List<Datum> args) {
        for (ScriptChunk script : scripts) {
            if (script.getScriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                continue;
            }
            // Use per-script Lnam when available, fall back to provided default
            ScriptNamesChunk names = script.file() != null
                ? script.file().getScriptNamesForScript(script) : defaultNames;
            if (names == null) names = defaultNames;
            ScriptChunk.Handler handler = script.findHandler(handlerName, names);
            if (handler != null) {
                try {
                    traceHandlerInvoke("movie", handlerName, script, 0, null);
                    vm.executeHandler(script, handler, args, null);
                } catch (Exception e) {
                    System.err.println("[EventDispatcher] Error in " + handlerName + ": " + e.getMessage());
                    if (debugEnabled) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Invoke a handler on a behavior instance.
     * In Director, if a handler exists and doesn't call pass(), propagation stops.
     */
    private void invokeHandler(BehaviorInstance instance, String handlerName, List<Datum> args) {
        if (instance == null || instance.getScript() == null) return;

        ScriptChunk script = instance.getScript();
        // Use the per-script Lnam (each Lctx has its own lnamSectionId)
        ScriptNamesChunk names = script.file() != null ? script.file().getScriptNamesForScript(script) : null;
        if (names == null) {
            return;
        }

        ScriptChunk.Handler handler = script.findHandler(handlerName, names);

        if (handler == null) {
            return;
        }

        // Handler exists - by default, stop propagation unless pass() is called
        stopPropagation = true;

        try {
            // Pass the instance as the receiver ('me')
            Datum receiver = instance.toDatum();
            traceHandlerInvoke("behavior", handlerName, script, instance.getSpriteNum(), instance);
            vm.executeHandler(script, handler, args, receiver);
        } catch (Exception e) {
            System.err.println("[EventDispatcher] Error in handler " + handlerName +
                               " on " + instance + ": " + e.getMessage());
            if (debugEnabled) {
                e.printStackTrace();
            }
        }
    }

    private boolean behaviorHasHandler(BehaviorInstance instance, String handlerName) {
        if (instance == null || instance.getScript() == null) return false;

        ScriptChunk script = instance.getScript();
        ScriptNamesChunk names = script.file() != null ? script.file().getScriptNamesForScript(script) : null;
        if (names == null) {
            return false;
        }

        return script.findHandler(handlerName, names) != null;
    }

    private Datum invokeScriptInstanceEvent(Datum.ScriptInstance instance, String handlerName, List<Datum> args) {
        if (AncestorChainWalker.hasHandler(instance, handlerName)) {
            return ControlFlowBuiltins.callHandlerOnInstance(vm, instance, handlerName, args);
        }
        return Datum.VOID;
    }

    private boolean scriptInstanceRespondsToEvent(Datum.ScriptInstance instance, String handlerName) {
        if (instance == null) {
            return false;
        }
        return AncestorChainWalker.hasHandler(instance, handlerName);
    }

    private boolean isMouseHandler(String handlerName) {
        return PlayerEvent.MOUSE_DOWN.getHandlerName().equals(handlerName)
                || PlayerEvent.MOUSE_UP.getHandlerName().equals(handlerName)
                || PlayerEvent.MOUSE_ENTER.getHandlerName().equals(handlerName)
                || PlayerEvent.MOUSE_LEAVE.getHandlerName().equals(handlerName)
                || PlayerEvent.MOUSE_WITHIN.getHandlerName().equals(handlerName)
                || "mouseUpOutSide".equals(handlerName)
                || PlayerEvent.MOUSE_UP_OUTSIDE.getHandlerName().equals(handlerName);
    }

    private Datum getScriptInstanceProcEntry(Datum.ScriptInstance instance, String handlerName) {
        if (instance == null || handlerName == null) {
            return Datum.VOID;
        }

        Datum procListDatum = AncestorChainWalker.getProperty(instance, "pProcList");
        if (!(procListDatum instanceof Datum.PropList procList)) {
            return Datum.VOID;
        }

        Datum direct = procList.get(handlerName);
        if (direct != null) {
            return direct;
        }

        String wanted = handlerName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < procList.size(); i++) {
            String key = procList.getKey(i);
            if (key != null && key.toLowerCase(Locale.ROOT).equals(wanted)) {
                Datum value = procList.getValue(i);
                return value != null ? value : Datum.VOID;
            }
        }
        return Datum.VOID;
    }

    private void traceHandlerInvoke(String scope, String handlerName, ScriptChunk script,
                                    int channel, BehaviorInstance instance) {
        if (!debugEnabled || !isInputTraceHandler(handlerName)) {
            return;
        }
        String scriptName = script != null ? script.getScriptName() : "";
        String scriptId = script != null ? String.valueOf(script.id()) : "";
        String instanceInfo = instance != null ? " instance=" + instance.getId() : "";
        System.out.println("[EventHandler] scope=" + scope
                + " event=" + handlerName
                + " ch=" + channel
                + " script=\"" + scriptName + "\""
                + " scriptId=" + scriptId
                + instanceInfo);
    }

    private void traceScriptInstanceInvoke(String scope, String handlerName,
                                           int channel, Datum.ScriptInstance instance) {
        if (!debugEnabled || !isInputTraceHandler(handlerName)) {
            return;
        }
        System.out.println("[EventHandler] scope=" + scope
                + " event=" + handlerName
                + " ch=" + channel
                + " instance=" + (instance != null ? instance.scriptId() : 0)
                + eventBrokerTraceInfo(handlerName, instance));
    }

    private void traceScriptInstanceResult(String scope, String handlerName,
                                           int channel, Datum.ScriptInstance instance, Datum result) {
        if (!debugEnabled || !isInputTraceHandler(handlerName)) {
            return;
        }
        System.out.println("[EventHandler] scope=" + scope
                + "-done event=" + handlerName
                + " ch=" + channel
                + " instance=" + (instance != null ? instance.scriptId() : 0)
                + " result=" + datumKey(result));
    }

    private void traceEventBrokerInvoke(String handlerName, int channel, Datum.ScriptInstance instance) {
        if (!debugEnabled || !isInputTraceHandler(handlerName) || !isEventBrokerLike(instance)) {
            return;
        }
        System.out.println("[EventBroker] invoke"
                + " event=" + handlerName
                + " ch=" + channel
                + " instance=" + (instance != null ? instance.scriptId() : 0)
                + eventBrokerTraceInfo(handlerName, instance));
    }

    private void traceEventBrokerResult(String handlerName, int channel,
                                        Datum.ScriptInstance instance, Datum result) {
        if (!debugEnabled || !isInputTraceHandler(handlerName) || !isEventBrokerLike(instance)) {
            return;
        }
        System.out.println("[EventBroker] done"
                + " event=" + handlerName
                + " ch=" + channel
                + " instance=" + (instance != null ? instance.scriptId() : 0)
                + " result=" + datumKey(result)
                + " stopped=" + vm.isEventStopped());
    }

    private boolean isInputTraceHandler(String handlerName) {
        return isMouseHandler(handlerName)
                || PlayerEvent.KEY_DOWN.getHandlerName().equals(handlerName)
                || PlayerEvent.KEY_UP.getHandlerName().equals(handlerName);
    }

    private boolean isEventBrokerLike(Datum.ScriptInstance instance) {
        if (instance == null) {
            return false;
        }
        return AncestorChainWalker.hasHandler(instance, "redirectEvent")
                && AncestorChainWalker.hasHandler(instance, "registerProcedure")
                && AncestorChainWalker.hasHandler(instance, "createProcListTemplate");
    }

    private String eventBrokerTraceInfo(String handlerName, Datum.ScriptInstance instance) {
        if (!isEventBrokerLike(instance)) {
            return "";
        }

        StringBuilder info = new StringBuilder();
        Datum brokerId = AncestorChainWalker.getProperty(instance, "id");
        Datum procListDatum = AncestorChainWalker.getProperty(instance, "pProcList");
        Datum procEntry = getScriptInstanceProcEntry(instance, handlerName);

        info.append(" brokerId=").append(datumDebug(brokerId));
        if (procListDatum instanceof Datum.PropList) {
            info.append(" proc=").append(datumDebug(procEntry));
        } else {
            info.append(" procList=VOID proc=VOID");
        }

        if (procEntry instanceof Datum.List procList && procList.items().size() >= 2) {
            info.append(" method=").append(datumDebug(procList.items().get(0)));
            info.append(" target=").append(datumDebug(procList.items().get(1)));
        }
        return info.toString();
    }

    private String datumKey(Datum datum) {
        if (datum == null || datum.isVoid()) {
            return "VOID";
        }
        return datum.toKeyName();
    }

    private String datumDebug(Datum datum) {
        if (datum == null || datum.isVoid()) {
            return "VOID";
        }
        if (datum instanceof Datum.List list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.items().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(datumDebug(list.items().get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        if (datum instanceof Datum.Str str) {
            return "\"" + str.value() + "\"";
        }
        return datum.toKeyName();
    }

    /**
     * Called by scripts to pass the event to the next handler.
     * This allows propagation to continue to subsequent handlers.
     */
    public void pass() {
        stopPropagation = false;
    }

    /**
     * Check if propagation was stopped.
     */
    public boolean isPropagationStopped() {
        return stopPropagation;
    }

    /**
     * Check if Lingo's stopEvent() was called during the current event dispatch.
     * Used by InputHandler to stop dispatching to further sprites.
     */
    public boolean isEventStopped() {
        return vm.isEventStopped();
    }

    /**
     * Reset the stopEvent flag at the start of each input event dispatch.
     */
    public void resetEventStopped() {
        vm.resetEventStopped();
    }

}
