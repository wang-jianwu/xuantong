package cloud.xuantong.probe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ProbeHttpServer implements AutoCloseable {
    private static final String PROMETHEUS_CONTENT_TYPE =
            "text/plain; version=0.0.4; charset=utf-8";
    private final HttpServer server;
    private final ExecutorService executor;
    private final ProbeMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean();

    ProbeHttpServer(String bindHost, int port, ProbeMetrics metrics) throws IOException {
        this.metrics = metrics;
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 16);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "xuantong-probe-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/metrics", this::metrics);
        server.createContext("/health", this::health);
    }

    void start() {
        server.start();
    }

    private void metrics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed\n");
            return;
        }
        respond(exchange, 200, PROMETHEUS_CONTENT_TYPE, metrics.render());
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed\n");
            return;
        }
        boolean healthy = metrics.healthy();
        String body = healthy
                ? "{\"status\":\"UP\"}\n"
                : "{\"status\":\"DOWN\",\"reason\":\""
                        + metrics.failureCategory() + "\"}\n";
        respond(exchange, healthy ? 200 : 503, "application/json; charset=utf-8", body);
    }

    private void respond(
            HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
    }
}
