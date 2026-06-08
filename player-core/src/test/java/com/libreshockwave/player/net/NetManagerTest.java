package com.libreshockwave.player.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetManagerTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void extensionlessHttpEndpointsFetchExactUrlAndCacheByFullQuery() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/origins-gamedata/external_variables/1", exchange -> {
            requestCount.incrementAndGet();
            respond(exchange, switch (String.valueOf(exchange.getRequestURI().getRawQuery())) {
                case "hotel=es&version=variables" -> "variables";
                case "hotel=es&version=texts" -> "texts";
                default -> null;
            });
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/origins-gamedata/external_variables/1";
        NetManager manager = new NetManager();

        int variablesTask = manager.preloadNetThing(baseUrl + "?hotel=es&version=variables");
        waitDone(manager, variablesTask);
        assertEquals("variables", manager.netTextResult(variablesTask));
        assertEquals(1, requestCount.get());

        int textsTask = manager.preloadNetThing(baseUrl + "?hotel=es&version=texts");
        waitDone(manager, textsTask);
        assertEquals("texts", manager.netTextResult(textsTask));
        assertEquals(2, requestCount.get());

        int repeatVariablesTask = manager.preloadNetThing(baseUrl + "?hotel=es&version=variables");
        assertTrue(manager.netDone(repeatVariablesTask));
        assertEquals("variables", manager.netTextResult(repeatVariablesTask));
        assertEquals(2, requestCount.get());
    }

    @Test
    void netTextResultNormalizesNetworkLineEndingsToDirectorReturn() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/external_texts.txt", exchange ->
                respond(exchange, "first=value\nsecond=value\r\nthird=value"));
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/external_texts.txt";
        NetManager manager = new NetManager();

        int taskId = manager.preloadNetThing(url);
        waitDone(manager, taskId);

        assertEquals("first=value\rsecond=value\rthird=value", manager.netTextResult(taskId));
    }

    private static void waitDone(NetManager manager, int taskId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.netDone(taskId)) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(manager.netDone(taskId), "network task did not finish");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        if (body == null) {
            byte[] bytes = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
