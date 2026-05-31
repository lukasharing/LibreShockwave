package com.libreshockwave.vm.builtin.string;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.datum.LingoException;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Output builtin functions (put, alert, pass).
 */
public final class OutputBuiltins {

    private OutputBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("put", OutputBuiltins::put);
        builtins.put("alert", OutputBuiltins::alert);
        // error() is commonly defined by authored movie scripts and must not be shadowed.
        // Note: pass is registered separately by LingoVM since it needs the passCallback
    }

    private static Datum put(LingoVM vm, List<Datum> args) {
        StringBuilder sb = new StringBuilder();
        for (Datum arg : args) {
            sb.append(arg.toStr()).append(' ');
        }
        String text = sb.toString().trim();
        if (!DebugConfig.isDebugPlaybackEnabled()) {
            return Datum.VOID;
        }
        System.out.println("[PUT] " + text);
        if (DebugConfig.isPauseOnScriptErrorEnabled() && shouldPauseOnAuthoredError(text)) {
            LingoException error = new LingoException(text);
            if (vm != null) {
                error.setLingoCallStack(vm.getCallStack());
                vm.fireTraceError("Authored Lingo error", error);
                Scope scope = vm.getCurrentScope();
                if (scope != null) {
                    scope.setReturned(true);
                }
                vm.setErrorState(true);
            }
        }
        return Datum.VOID;
    }

    private static boolean shouldPauseOnAuthoredError(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        if (!lower.startsWith("error:")) {
            return false;
        }
        boolean authoredMajor = lower.contains("method:  major")
                || lower.contains("method:\tmajor")
                || lower.contains("method: major");
        return lower.contains("fatal")
                    || (authoredMajor && DebugConfig.isPauseOnAuthoredMajorEnabled());
    }

    private static Datum alert(LingoVM vm, List<Datum> args) {
        String msg = args.isEmpty() ? "" : args.get(0).toStr();
        // Try alertHook first — if it handles the alert, suppress the default output
        if (vm.fireAlertHook(msg)) {
            return Datum.VOID;
        }
        System.out.println("[ALERT] " + msg);
        return Datum.VOID;
    }
}
