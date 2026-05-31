package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.builtin.net.NetBuiltins;
import com.libreshockwave.vm.datum.Datum;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Minimal Director Curl Xtra compatibility layer.
 *
 * Authored Download CURL wrapper scripts own the cast/member import logic; the
 * Xtra only needs to expose libcurl-like handles, start the request, and call
 * the Lingo callbacks when the network provider completes.
 */
public final class CurlXtra implements Xtra {

    private static final int CURLOPT_URL = 10002;

    private final Supplier<NetBuiltins.NetProvider> netProviderSupplier;
    private final ScriptCallback scriptCallback;
    private final Map<Integer, InstanceState> instances = new HashMap<>();
    private int nextInstanceId = 1;

    public CurlXtra(Supplier<NetBuiltins.NetProvider> netProviderSupplier,
                    ScriptCallback scriptCallback) {
        this.netProviderSupplier = netProviderSupplier;
        this.scriptCallback = scriptCallback;
    }

    @Override
    public String getName() {
        return "Curl";
    }

    @Override
    public int createInstance(List<Datum> args) {
        int id = nextInstanceId++;
        instances.put(id, new InstanceState());
        return id;
    }

    @Override
    public void destroyInstance(int instanceId) {
        instances.remove(instanceId);
    }

    @Override
    public Datum callHandler(int instanceId, String handlerName, List<Datum> args) {
        InstanceState state = instances.get(instanceId);
        if (state == null) {
            return Datum.VOID;
        }

        return switch (handlerName.toLowerCase()) {
            case "setoption" -> setOption(state, args);
            case "setdestinationfile" -> setDestinationFile(state, args);
            case "setprogresscallback" -> setProgressCallback(state, args);
            case "execasync" -> execAsync(state, args);
            case "close" -> close(state);
            default -> Datum.VOID;
        };
    }

    @Override
    public Datum getProperty(int instanceId, String propertyName) {
        InstanceState state = instances.get(instanceId);
        if (state == null) {
            return Datum.VOID;
        }
        return switch (propertyName.toLowerCase()) {
            case "url" -> Datum.of(state.url);
            case "destinationfile" -> Datum.of(state.destinationFile);
            default -> Datum.VOID;
        };
    }

    @Override
    public void setProperty(int instanceId, String propertyName, Datum value) {
        InstanceState state = instances.get(instanceId);
        if (state == null) {
            return;
        }
        if ("url".equalsIgnoreCase(propertyName)) {
            state.url = value.toStr();
        } else if ("destinationfile".equalsIgnoreCase(propertyName)) {
            state.destinationFile = value.toStr();
        }
    }

    @Override
    public void tick() {
        NetBuiltins.NetProvider provider = netProviderSupplier.get();
        if (provider == null) {
            return;
        }

        for (InstanceState state : instances.values()) {
            if (!state.running || state.completed || state.taskId <= 0) {
                continue;
            }

            Datum status = provider.getStreamStatusDatum(state.taskId);
            int bytesSoFar = propInt(status, "bytesSoFar");
            int bytesTotal = propInt(status, "bytesTotal");
            fireProgress(state, bytesTotal, bytesSoFar);

            if (!provider.netDone(state.taskId)) {
                continue;
            }

            state.completed = true;
            state.running = false;
            int error = provider.netError(state.taskId);
            Datum result;
            if (error == 0 && state.streamResult) {
                result = Datum.of(normalizeDirectorText(provider.netTextResult(state.taskId)));
            } else {
                result = Datum.of(error);
            }
            fireDone(state, result);
        }
    }

    private Datum setOption(InstanceState state, List<Datum> args) {
        if (args.size() >= 2) {
            int option = args.get(0).toInt();
            Datum value = args.get(1);
            state.options.put(option, value);
            if (option == CURLOPT_URL) {
                state.url = value.toStr();
            }
        }
        return Datum.ZERO;
    }

    private Datum setDestinationFile(InstanceState state, List<Datum> args) {
        state.destinationFile = args.isEmpty() ? "" : args.get(0).toStr();
        return Datum.ZERO;
    }

    private Datum setProgressCallback(InstanceState state, List<Datum> args) {
        if (args.size() >= 2) {
            state.progressHandler = args.get(0).toKeyName();
            state.progressTarget = args.get(1);
        }
        return Datum.ZERO;
    }

    private Datum execAsync(InstanceState state, List<Datum> args) {
        if (args.size() >= 2) {
            state.doneHandler = args.get(0).toKeyName();
            state.doneTarget = args.get(1);
        }
        state.streamResult = args.size() < 3 || args.get(2).isTruthy();

        NetBuiltins.NetProvider provider = netProviderSupplier.get();
        if (provider == null || state.url == null || state.url.isEmpty()) {
            state.completed = true;
            fireDone(state, Datum.of(-1));
            return Datum.of(-1);
        }

        state.taskId = provider.preloadNetThing(state.url);
        state.running = true;
        state.completed = false;
        return Datum.ZERO;
    }

    private Datum close(InstanceState state) {
        state.running = false;
        state.completed = true;
        return Datum.ZERO;
    }

    private void fireProgress(InstanceState state, int total, int now) {
        if (state.progressHandler == null || state.progressTarget == null) {
            return;
        }
        scriptCallback.invoke(state.progressTarget, state.progressHandler,
                List.of(Datum.of(total), Datum.of(now)));
    }

    private void fireDone(InstanceState state, Datum result) {
        if (state.doneHandler == null || state.doneTarget == null) {
            return;
        }
        scriptCallback.invoke(state.doneTarget, state.doneHandler, List.of(result));
    }

    private static int propInt(Datum value, String key) {
        if (value instanceof Datum.PropList pl) {
            Datum entry = pl.get(key);
            if (entry != null) {
                return entry.toInt();
            }
        }
        return 0;
    }

    private static String normalizeDirectorText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\r\n", "\r").replace('\n', '\r');
    }

    private static final class InstanceState {
        final Map<Integer, Datum> options = new HashMap<>();
        String url = "";
        String destinationFile = "";
        String progressHandler;
        Datum progressTarget;
        String doneHandler;
        Datum doneTarget;
        boolean streamResult = true;
        int taskId;
        boolean running;
        boolean completed;
    }
}
