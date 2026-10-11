package org.sebi.skilllab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Read-only, loopback-only dashboard. No credentials, generation, or judge calls. */
public final class RunDashboard {

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = Integer.parseInt(System.getProperty("skill.lab.dashboard.port", "8765"));
        HttpServer server = start(Path.of("target"), port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "dashboard-shutdown"));
        System.out.println("Skill dashboard: http://127.0.0.1:" + server.getAddress().getPort()
            + "/ (read-only; Ctrl+C to stop)");
        try {
            new CountDownLatch(1).await();
        } finally {
            server.stop(0);
        }
    }

    static HttpServer start(Path target, int port) throws IOException {
        byte[] page;
        try (InputStream resource = RunDashboard.class.getResourceAsStream("/dashboard.html")) {
            if (resource == null) throw new IOException("Missing dashboard.html; run mvn test-compile first");
            page = resource.readAllBytes();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                        + "connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    send(exchange, 405, "text/plain", "Read-only dashboard".getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestURI().getPath().equals("/")) {
                    send(exchange, 200, "text/html", page);
                } else if (exchange.getRequestURI().getPath().equals("/api/runs")) {
                    byte[] body;
                    try {
                        body = RunReports.history(target).toString().getBytes(StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        System.err.println("Cannot read dashboard history: " + e.getMessage());
                        send(exchange, 500, "text/plain", "Cannot read run history; see server console".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    send(exchange, 200, "application/json", body);
                } else {
                    send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.start();
        return server;
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
