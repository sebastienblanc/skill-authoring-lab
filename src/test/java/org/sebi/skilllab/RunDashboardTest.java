package org.sebi.skilllab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RunDashboardTest {
    @TempDir Path temp;
    private HttpServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws IOException {
        server = RunDashboard.start(temp, 0);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stop() throws InterruptedException {
        server.stop(0);
        client.shutdownNow();
        assertTrue(client.awaitTermination(Duration.ofSeconds(2)));
    }

    @Test
    void servesUiAndRefreshesHistoryWithoutRestarting() throws Exception {
        assertTrue(server.getAddress().getAddress().isLoopbackAddress());
        var page = request("/", "GET");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Better skills. Measured."));
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(new ObjectMapper().readTree(request("/api/runs", "GET").body()).path("runs").isEmpty());
        RunReports.write(temp.resolve("skill-lab/runs/one.json"), RunReportsTest.legacy(temp.resolve("workspace")));
        assertEquals(1, new ObjectMapper().readTree(request("/api/runs", "GET").body()).path("runs").size());
    }

    @Test
    void exposesNoFilesOrMutatingRoutes() throws Exception {
        assertEquals(404, request("/.env", "GET").statusCode());
        assertEquals(404, request("/pom.xml", "GET").statusCode());
        assertEquals(405, request("/api/runs", "POST").statusCode());
    }

    private HttpResponse<String> request(String path, String method) throws Exception {
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .timeout(Duration.ofSeconds(2)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString()).get(3, TimeUnit.SECONDS);
    }
}
