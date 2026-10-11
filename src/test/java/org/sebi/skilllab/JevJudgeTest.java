package org.sebi.skilllab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class JevJudgeTest {

    private static final String VERDICT = """
        {"model":"fixture","answers":{
          "delegation":{"type":"noul","noul":0.9},
          "descriptions":{"type":"score","score":1.5,"confidence":0.8,
            "probabilities":{"0":0.1,"1":0.3,"2":0.6},"legend":{"0":"missing","1":"partial","2":"complete"}},
          "defect":{"type":"choice","choice":"none","confidence":0.9,"probabilities":{"none":0.9,"logic":0.1}}
        },"usage":{"input_tokens":42}}
        """;

    @TempDir Path temp;
    private HttpServer server;
    private ExecutorService executor;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }

    @Test
    void retriesThenParsesTypedAnswersAndKeepsTraces() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                reply(exchange, 429, "busy");
            } else {
                reply(exchange, 200, VERDICT);
            }
        });

        var verdict = evaluate(Duration.ofSeconds(5));

        assertEquals(2, attempts.get());
        assertEquals(0.9, verdict.noul("delegation").noul());
        assertEquals(0.75, verdict.score("descriptions").normalized());
        assertEquals("none", verdict.choice("defect").choice());
        assertEquals(42, verdict.inputTokens());
        assertTrue(request.get().contains("\"java_sources\""));
        assertTrue(Files.readString(temp.resolve("jev-response.json")).contains("\"fixture\""));
        assertFalse(Files.readString(temp.resolve("jev-request.json")).contains("fixture-secret"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void boundsBothWaitingForHeadersAndAStalledResponseBody(boolean sendHeaders) {
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                }
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var error = assertTimeout(Duration.ofSeconds(3), () -> assertThrows(IllegalStateException.class,
            () -> evaluate(Duration.ofMillis(500))));
        assertTrue(error.getMessage().contains("Jev"));
        assertTrue(error.getMessage().contains("jev-request.json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"86400", "9223372036854775807"})
    void rejectsRetryAfterBeyondTheDeadlineWithoutSleeping(String retryAfter) {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
            reply(exchange, 429, "busy");
        });

        var error = assertTimeout(Duration.ofSeconds(3), () -> assertThrows(IllegalStateException.class,
            () -> evaluate(Duration.ofSeconds(5))));
        assertTrue(error.getMessage().contains("exceeds the remaining overall timeout"));
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 529, 401})
    void stopsAfterFourRetryableAttemptsOrOneAuthenticationFailure(int code) {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "0");
            reply(exchange, code, "fixture failure");
        });

        var error = assertThrows(IllegalStateException.class, () -> evaluate(Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("HTTP " + code));
        assertEquals(code == 401 ? 1 : 4, attempts.get());
    }

    @Test
    void overallBudgetIncludesPreviousRetryDelays() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "1");
            reply(exchange, 429, "busy");
        });

        var error = assertTimeout(Duration.ofSeconds(3), () -> assertThrows(IllegalStateException.class,
            () -> evaluate(Duration.ofMillis(1_500))));
        assertTrue(error.getMessage().contains("exceeds the remaining overall timeout"));
        assertEquals(2, attempts.get(), "The second retry must use what remains of the original budget");
    }

    @Test
    void interruptionPreservesInterruptStatus() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try (exchange) {
                received.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                evaluate(Duration.ofMinutes(1));
                failure.set(new AssertionError("Judge unexpectedly completed"));
            } catch (IllegalStateException e) {
                if (!Thread.currentThread().isInterrupted())
                    failure.set(new AssertionError("Interrupt status was lost", e));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(3_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
        } finally {
            worker.interrupt();
            worker.join(3_000);
        }
    }

    @Test
    void acceptsHttpDateRetryAfterAndFallsBackForInvalidHeaders() {
        String date = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        Duration delay = JevJudge.retryDelay(date, Duration.ofSeconds(1));
        assertTrue(delay.compareTo(Duration.ofSeconds(55)) > 0);
        assertTrue(delay.compareTo(Duration.ofSeconds(60)) <= 0);
        assertEquals(Duration.ofSeconds(1), JevJudge.retryDelay("invalid", Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(1), JevJudge.retryDelay("-1", Duration.ofSeconds(1)));
    }

    private JevJudge.Verdict evaluate(Duration timeout) {
        return new JevJudge(temp, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone",
            "fixture", "fixture-secret", timeout).evaluate(Map.of("java_sources", Map.of("Example.java", "class Example {}")),
                Map.of("delegation", JevJudge.noul("Does it delegate?"),
                    "descriptions", JevJudge.score("Useful descriptions?", "missing", "partial", "complete"),
                    "defect", JevJudge.choice("Main defect?", Map.of("none", "No defect", "logic", "Inline logic"))));
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
