package com.alexastudillo.companyregistry.contract;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real HTTP provider stub, reachable by both JVM and independently launched
 * native processes.
 */
public final class ReferenceStubServer implements AutoCloseable {
    public record Reply(int status, String body, long delayMillis) {
    }

    public record Request(String path, Map<String, List<String>> headers, String query) {
        public String header(String key) {
            var values = headers.get(key);
            return values == null ? null : values.getFirst();
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public ReferenceStubServer() throws java.io.IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var headers = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((key, values) -> headers.put(key, List.copyOf(values)));
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getRawQuery();
            requests.add(new Request(path, headers, query));
            var gate = gates.remove(path);
            if (gate != null) {
                gate.arrived().countDown();
                gate.reply().whenComplete((reply, failure) -> sendReply(exchange,
                        failure == null ? reply : new Reply(503, "{}", 0)));
                return;
            }
            var reply = replies.getOrDefault(path + (query == null ? "" : "?" + query),
                    replies.getOrDefault(path, new Reply(404, "{\"status\":404,\"code\":\"not-found\"}", 0)));
            if (reply.delayMillis() > 0) {
                scheduler.schedule(() -> sendReply(exchange, reply), reply.delayMillis(), TimeUnit.MILLISECONDS);
            } else {
                sendReply(exchange, reply);
            }
        });
        server.start();
    }

    private void sendReply(com.sun.net.httpserver.HttpExchange exchange, Reply reply) {
        try (exchange) {
            var body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
        } catch (java.io.IOException e) {
            // Test stub — log and move on
            e.printStackTrace();
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reply(String path, int status, String body) {
        replies.put(path, new Reply(status, body, 0));
    }

    public void reply(String path, Reply reply) {
        replies.put(path, reply);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Hold the next matching response without blocking a server thread or relying on sleeps. */
    public record Gate(CountDownLatch arrived, CompletableFuture<Reply> reply) {}

    public Gate holdNext(String path) {
        var gate = new Gate(new CountDownLatch(1), new CompletableFuture<>());
        if (gates.putIfAbsent(path, gate) != null) throw new IllegalStateException("Provider response already held");
        return gate;
    }

    public void reset() {
        replies.clear();
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        scheduler.shutdownNow();
        executor.shutdownNow();
    }
}

