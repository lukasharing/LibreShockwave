package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.builtin.net.NetBuiltins;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CurlXtraTest {

    @Test
    void authoredNumericAliasesStartDownloadAndReturnStreamedText() {
        FakeNetProvider provider = new FakeNetProvider();
        List<Callback> callbacks = new ArrayList<>();
        CurlXtra xtra = new CurlXtra(() -> provider,
                (target, handlerName, args) -> callbacks.add(new Callback(target, handlerName, args)));
        int instanceId = xtra.createInstance(List.of());

        xtra.callHandler(instanceId, "#2216", List.of(Datum.of(10002), Datum.of("external_texts.txt")));
        xtra.callHandler(instanceId, "#2219", List.of(Datum.symbol("2200"), Datum.of("progressTarget")));
        xtra.callHandler(instanceId, "#2221", List.of(Datum.symbol("2201"), Datum.of("doneTarget"), Datum.TRUE));

        assertEquals("external_texts.txt", provider.lastUrl);

        provider.bytesSoFar = 128;
        provider.bytesTotal = 512;
        xtra.tick();

        assertEquals(1, callbacks.size());
        assertEquals("progressTarget", callbacks.get(0).target().toStr());
        assertEquals("2200", callbacks.get(0).handlerName());
        assertEquals(512, callbacks.get(0).args().get(0).toInt());
        assertEquals(128, callbacks.get(0).args().get(1).toInt());

        provider.done = true;
        provider.result = "one\ntwo";
        xtra.tick();

        assertEquals(3, callbacks.size());
        Callback done = callbacks.get(2);
        assertEquals("doneTarget", done.target().toStr());
        assertEquals("2201", done.handlerName());
        assertEquals("one\rtwo", done.args().get(0).toStr());
    }

    @Test
    void authoredNumericAliasesReturnErrorCodeForFileDownloads() {
        FakeNetProvider provider = new FakeNetProvider();
        List<Callback> callbacks = new ArrayList<>();
        CurlXtra xtra = new CurlXtra(() -> provider,
                (target, handlerName, args) -> callbacks.add(new Callback(target, handlerName, args)));
        int instanceId = xtra.createInstance(List.of());

        xtra.callHandler(instanceId, "2216", List.of(Datum.of(10002), Datum.of("room.cct")));
        xtra.callHandler(instanceId, "2217", List.of(Datum.of("/tmp/room.cct")));
        xtra.callHandler(instanceId, "2221", List.of(Datum.symbol("2201"), Datum.of("doneTarget"), Datum.FALSE));

        provider.done = true;
        xtra.tick();

        assertEquals("/tmp/room.cct", xtra.getProperty(instanceId, "destinationFile").toStr());
        assertEquals("/tmp/room.cct", provider.alias);
        assertArrayEquals(provider.bytes, provider.aliasedBytes);
        assertEquals(1, callbacks.size());
        assertEquals(0, callbacks.get(0).args().get(0).toInt());
    }

    private record Callback(Datum target, String handlerName, List<Datum> args) {}

    private static final class FakeNetProvider implements NetBuiltins.NetProvider {
        String lastUrl;
        boolean done;
        int bytesSoFar;
        int bytesTotal;
        int error;
        String result = "";
        byte[] bytes = new byte[] { 9, 8, 7 };
        String alias;
        byte[] aliasedBytes;

        @Override
        public int preloadNetThing(String url) {
            lastUrl = url;
            return 7;
        }

        @Override
        public int postNetText(String url, String postData) {
            return 0;
        }

        @Override
        public boolean netDone(Integer taskId) {
            assertEquals(7, taskId);
            return done;
        }

        @Override
        public String netTextResult(Integer taskId) {
            assertEquals(7, taskId);
            return result;
        }

        @Override
        public byte[] netBytesResult(Integer taskId) {
            assertEquals(7, taskId);
            return bytes;
        }

        @Override
        public void aliasCachedResult(Integer taskId, String alias) {
            assertEquals(7, taskId);
            this.alias = alias;
            this.aliasedBytes = netBytesResult(taskId);
        }

        @Override
        public int netError(Integer taskId) {
            assertEquals(7, taskId);
            return error;
        }

        @Override
        public String getStreamStatus(Integer taskId) {
            return done ? "Complete" : "Loading";
        }

        @Override
        public Datum getStreamStatusDatum(Integer taskId) {
            assertEquals(7, taskId);
            LinkedHashMap<String, Datum> props = new LinkedHashMap<>();
            props.put("URL", Datum.of(lastUrl));
            props.put("state", Datum.of(done ? "Complete" : "Loading"));
            props.put("bytesSoFar", Datum.of(bytesSoFar));
            props.put("bytesTotal", Datum.of(bytesTotal));
            props.put("error", Datum.of(error == 0 ? "OK" : String.valueOf(error)));
            return Datum.propList(props);
        }
    }
}
