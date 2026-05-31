package com.libreshockwave.vm.builtin.flow;

import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.builtin.movie.MoviePropertyProvider;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.datum.DatumFormatter;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;
import com.libreshockwave.vm.util.AncestorChainWalker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Control flow builtin functions.
 * Includes: return, halt, abort, nothing, param
 */
public final class ControlFlowBuiltins {



    private ControlFlowBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("return", ControlFlowBuiltins::returnValue);
        builtins.put("halt", ControlFlowBuiltins::halt);
        builtins.put("abort", ControlFlowBuiltins::abort);
        builtins.put("try", ControlFlowBuiltins::tryBlock);
        builtins.put("catch", ControlFlowBuiltins::catchBlock);
        builtins.put("nothing", ControlFlowBuiltins::nothing);
        builtins.put("param", ControlFlowBuiltins::param);
        builtins.put("go", ControlFlowBuiltins::go);
        builtins.put("call", ControlFlowBuiltins::call);
        // Note: receiveUpdate/removeUpdate are intentionally NOT builtins.
        // Many movies define these as authored movie-script handlers that route
        // through their own object/update managers. Registering builtins here
        // would shadow that Lingo layer and pass raw IDs into VM internals.
    }

    /**
     * return(value)
     * Returns early from the current handler with the specified value.
     */
    private static Datum returnValue(LingoVM vm, List<Datum> args) {
        Scope scope = vm.getCurrentScope();
        if (scope != null) {
            Datum returnVal = args.isEmpty() ? Datum.VOID : args.get(0);
            scope.setReturnValue(returnVal);
            scope.setReturned(true);
        }
        return Datum.VOID;
    }

    /**
     * halt()
     * Stops movie playback (does nothing in current implementation).
     */
    private static Datum halt(LingoVM vm, List<Datum> args) {
        // In a full implementation, this would stop movie playback
        return Datum.VOID;
    }

    /**
     * abort()
     * Aborts the current script execution.
     */
    private static Datum abort(LingoVM vm, List<Datum> args) {
        Scope scope = vm.getCurrentScope();
        if (scope != null) {
            scope.setReturned(true);
        }
        return Datum.VOID;
    }

    /**
     * Director's try()/catch() pair is commonly used as a lightweight error
     * sentinel around optional platform features. try() starts a protected
     * region by clearing the pending script error state; catch() reports
     * whether an error occurred and clears it so subsequent authored code can
     * continue in the same handler.
     */
    private static Datum tryBlock(LingoVM vm, List<Datum> args) {
        vm.resetErrorState();
        return Datum.VOID;
    }

    private static Datum catchBlock(LingoVM vm, List<Datum> args) {
        boolean caught = vm.isInErrorState();
        vm.resetErrorState();
        return caught ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * nothing
     * Does nothing - used as a placeholder.
     */
    private static Datum nothing(LingoVM vm, List<Datum> args) {
        return Datum.VOID;
    }

    /**
     * go(frameOrLabel)
     * Navigate to a frame by number, label, or symbol (#next, #previous, #loop).
     * Matches dirplayer-rs MovieHandlers::go().
     */
    private static Datum go(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider == null) return Datum.VOID;

        Datum arg = args.get(0);
        if (arg instanceof Datum.Int i) {
            provider.goToFrame(i.value());
        } else if (arg instanceof Datum.Symbol sym) {
            switch (sym.name().toLowerCase()) {
                case "next" -> provider.goToFrame(provider.getMovieProp("frame").toInt() + 1);
                case "previous" -> provider.goToFrame(Math.max(1, provider.getMovieProp("frame").toInt() - 1));
                case "loop" -> provider.goToFrame(provider.getMovieProp("frame").toInt());
                default -> provider.goToLabel(sym.name());
            }
        } else if (arg instanceof Datum.Str s) {
            provider.goToLabel(s.value());
        } else {
            // Fallback: try as int
            int frame = arg.toInt();
            if (frame > 0) {
                provider.goToFrame(frame);
            }
        }
        return Datum.VOID;
    }

    /**
     * param(n)
     * Returns the nth parameter passed to the current handler.
     * Parameter numbers are 1-indexed in Lingo.
     */
    private static Datum param(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        Scope scope = vm.getCurrentScope();
        if (scope == null) {
            return Datum.VOID;
        }

        int paramNumber = args.get(0).toInt();
        List<Datum> handlerArgs = scope.getArguments();

        // Lingo uses 1-indexed parameters
        int index = paramNumber - 1;
        if (index >= 0 && index < handlerArgs.size()) {
            return handlerArgs.get(index);
        }

        return Datum.VOID;
    }

    /**
     * call(#handlerName, objectList, extraArgs...)
     * Calls the named handler on every ScriptInstance in the list.
     * Walks ancestor chain to find the handler on each instance.
     * This is a core Director function used by the Object Manager pattern.
     */
    private static Datum call(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;

        String handlerName;
        Datum first = args.get(0);
        if (first instanceof Datum.Symbol sym) {
            handlerName = sym.name();
        } else {
            handlerName = first.toStr();
        }

        Datum targetList = args.get(1);
        List<Datum> extraArgs = args.size() > 2 ? args.subList(2, args.size()) : List.of();

        traceCall(vm, handlerName, targetList, extraArgs);

        Datum lastResult = Datum.VOID;
        if (targetList instanceof Datum.ScriptInstance instance) {
            // call(#handler, singleObject, args...) — call on one instance
            lastResult = callHandlerOnInstance(vm, instance, handlerName, extraArgs);
        } else if (targetList instanceof Datum.List list) {
            // Snapshot the list to avoid ConcurrentModificationException if handlers modify it
            List<Datum> snapshot = new ArrayList<>(list.items());
            for (Datum target : snapshot) {
                lastResult = callOnTarget(vm, target, handlerName, extraArgs);
            }
        } else if (targetList instanceof Datum.PropList propList) {
            // Director also supports call(#handler, propList) — iterates through values
            List<Datum> snapshot = new ArrayList<>(propList.size());
            for (Datum.PropEntry entry : propList.entries()) snapshot.add(entry.value());
            for (Datum target : snapshot) {
                lastResult = callOnTarget(vm, target, handlerName, extraArgs);
            }
        } else {
            // Single non-list target (e.g. sprite channel number)
            lastResult = callOnTarget(vm, targetList, handlerName, extraArgs);
        }
        return lastResult;
    }

    /**
     * Call a handler on a single target, which may be a ScriptInstance or a
     * sprite channel number (integer).  Director's {@code call} dispatches to
     * behaviors on sprite channels — we resolve those via SpritePropertyProvider.
     */
    private static Datum callOnTarget(LingoVM vm, Datum target, String handlerName, List<Datum> extraArgs) {
        if (target instanceof Datum.ScriptInstance instance) {
            return callHandlerOnInstance(vm, instance, handlerName, extraArgs);
        }
        // Sprite reference → extract channel number directly
        // (SpriteRef.toInt() returns 0 via default, so we must use channelNum())
        int channel;
        if (target instanceof Datum.SpriteRef sr) {
            channel = sr.channelNum();
        } else {
            // Integer target → sprite channel number
            channel = target.toInt();
        }
        if (channel > 0) {
            var provider = com.libreshockwave.vm.builtin.sprite.SpritePropertyProvider.getProvider();
            if (provider != null) {
                java.util.List<Datum> scripts = provider.getScriptInstanceList(channel);
                if (scripts != null && !scripts.isEmpty()) {
                    Datum lastResult = Datum.VOID;
                    for (Datum si : scripts) {
                        if (si instanceof Datum.ScriptInstance instance
                                && AncestorChainWalker.hasHandler(instance, handlerName)) {
                            lastResult = callHandlerOnInstance(vm, instance, handlerName, extraArgs);
                        }
                    }
                    return lastResult;
                }
            }
        }
        return Datum.VOID;
    }

    /**
     * Call a handler on a script instance, walking the ancestor chain to find it.
     * Returns the handler's return value, or VOID if the handler was not found or threw.
     * Public so the player-core event dispatcher can use it for scriptInstanceList.
     */
    public static Datum callHandlerOnInstance(LingoVM vm, Datum.ScriptInstance instance,
                                               String handlerName, List<Datum> extraArgs) {
        try {
            traceHandlerDispatch(vm, handlerName, instance, extraArgs);
            Datum result = AncestorChainWalker.invokeHandlerWithResult(vm, instance, handlerName, extraArgs);
            return result != null ? result : Datum.VOID;
        } catch (Exception e) {
            System.err.println("[callHandlerOnInstance] Exception in '" + handlerName + "': " + e.getMessage());
            return Datum.VOID;
        }
    }

    private static void traceCall(LingoVM vm, String handlerName, Datum targetList, List<Datum> extraArgs) {
        if (!shouldTraceDispatch(vm, handlerName)) {
            return;
        }
        System.out.println("[TRACE] call(#" + handlerName
                + ", target=" + DatumFormatter.formatBrief(targetList)
                + ", args=" + formatArgs(extraArgs) + ")");
    }

    private static void traceHandlerDispatch(LingoVM vm, String handlerName, Datum.ScriptInstance receiver,
                                             List<Datum> extraArgs) {
        if (!shouldTraceDispatch(vm, handlerName)) {
            return;
        }
        System.out.println("[TRACE] callHandlerOnInstance handler=#" + handlerName
                + " receiver=<script#" + receiver.scriptId() + ">"
                + " args=" + formatArgs(extraArgs));
    }

    private static boolean shouldTraceDispatch(LingoVM vm, String handlerName) {
        if (!DebugConfig.isDebugPlaybackEnabled() || vm == null) {
            return false;
        }
        var tracedHandlers = vm.getTracedHandlers();
        if (tracedHandlers.isEmpty()) {
            return false;
        }
        return tracedHandlers.contains("call")
                || tracedHandlers.contains(LingoVM.normalizeLookupName(handlerName));
    }

    private static String formatArgs(List<Datum> args) {
        if (args == null || args.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(args.size(), 6);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(DatumFormatter.formatBrief(args.get(i)));
        }
        if (args.size() > limit) {
            sb.append(", ... ").append(args.size() - limit).append(" more");
        }
        return sb.append(']').toString();
    }

}
