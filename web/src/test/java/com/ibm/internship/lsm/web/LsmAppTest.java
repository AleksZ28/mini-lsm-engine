package com.ibm.internship.lsm.web;

import com.ibm.internship.lsm.core.MiniLsmEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LsmAppTest {

    private MiniLsmEngine engine;
    private LsmApp app;
    private String baseUrl;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Path walPath = tempDir.resolve("test.wal");
        Path sstPath = tempDir.resolve("test.sst");
        engine = new MiniLsmEngine(walPath, sstPath);

        app = new LsmApp(engine);
        app.start(0); // Port 0 assigns a free ephemeral port

        baseUrl = "http://localhost:" + app.port();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        app.stop();
        engine.close();
    }

    @Test
    @DisplayName("Should return 200 OK on health-check root route")
    void shouldReturnHealthCheck() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Server is working", response.body());
    }

    @Test
    @DisplayName("Should create, read, and delete entry over HTTP")
    void shouldPerformCrudLifecycle() throws Exception {
        // 1. POST /api/entries -> 201 Created
        HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\": \"user:1\", \"value\": \"Aleks\"}"))
                .build();

        HttpResponse<String> postResponse = httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, postResponse.statusCode());

        // 2. GET /api/entries/user:1 -> 200 OK with value
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries/user:1"))
                .GET()
                .build();

        HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getResponse.statusCode());
        assertEquals("Aleks", getResponse.body());

        // 3. DELETE /api/entries/user:1 -> 204 No Content
        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries/user:1"))
                .DELETE()
                .build();

        HttpResponse<String> deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, deleteResponse.statusCode());

        // 4. GET /api/entries/user:1 after delete -> 404 Not Found
        HttpResponse<String> getAfterDelete = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, getAfterDelete.statusCode());
    }

    @Test
    @DisplayName("Should return 404 Not Found for non-existent key")
    void shouldReturn404ForNonExistentKey() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries/non-existent-key"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    @DisplayName("Should return 400 Bad Request when key is null in POST")
    void shouldReturn400OnNullKey() throws Exception {
        HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\": null, \"value\": \"someValue\"}"))
                .build();

        HttpResponse<String> postResponse = httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, postResponse.statusCode());
        assertTrue(postResponse.body().contains("key cannot be null"));
    }

    @Test
    @DisplayName("Should serve static dashboard index.html")
    void shouldServeStaticDashboard() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/index.html"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("MINI-LSM STORAGE ENGINE DASHBOARD"));
    }

    @Test
    @DisplayName("Should successfully trigger flush over HTTP POST /api/flush")
    void shouldTriggerFlushOverHttp() throws Exception {
        HttpRequest flushRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/flush"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(flushRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
    }

    @Test
    @DisplayName("Should return engine stats over HTTP GET /api/stats")
    void shouldReturnEngineStatsOverHttp() throws Exception {
        // 1. Initial empty stats
        HttpRequest getStatsRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/stats"))
                .GET()
                .build();

        HttpResponse<String> initialResponse = httpClient.send(getStatsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, initialResponse.statusCode());
        assertTrue(initialResponse.body().contains("\"memTableSizeBytes\":0"));
        assertTrue(initialResponse.body().contains("\"memTableEntryCount\":0"));
        assertTrue(initialResponse.body().contains("\"sstableCount\":0"));

        // 2. Add an entry
        HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\": \"user:1\", \"value\": \"Aleks\"}"))
                .build();
        assertEquals(201, httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 3. Stats after insert -> MemTable has entries and size > 0
        HttpResponse<String> afterInsertResponse = httpClient.send(getStatsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, afterInsertResponse.statusCode());
        assertTrue(afterInsertResponse.body().contains("\"memTableEntryCount\":1"));
        assertTrue(afterInsertResponse.body().contains("\"sstableCount\":0"));

        // 4. Trigger flush -> SSTable created, MemTable reset
        HttpRequest flushRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/flush"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(204, httpClient.send(flushRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 5. Stats after flush -> sstableCount: 1, memTableEntryCount: 0
        HttpResponse<String> afterFlushResponse = httpClient.send(getStatsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, afterFlushResponse.statusCode());
        assertTrue(afterFlushResponse.body().contains("\"memTableEntryCount\":0"));
        assertTrue(afterFlushResponse.body().contains("\"sstableCount\":1"));
        assertTrue(afterFlushResponse.body().contains("1.sst"));
    }

    @Test
    @DisplayName("Should successfully trigger compaction over HTTP POST /api/compact")
    void shouldTriggerCompactOverHttp() throws Exception {
        HttpRequest getStatsRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/stats"))
                .GET()
                .build();

        // 1. Create 1st SSTable
        HttpRequest post1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\": \"k1\", \"value\": \"v1\"}"))
                .build();
        assertEquals(201, httpClient.send(post1, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest flushRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/flush"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(204, httpClient.send(flushRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 2. Create 2nd SSTable
        HttpRequest post2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/entries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"key\": \"k2\", \"value\": \"v2\"}"))
                .build();
        assertEquals(201, httpClient.send(post2, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(204, httpClient.send(flushRequest, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpResponse<String> beforeCompactStats = httpClient.send(getStatsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, beforeCompactStats.statusCode());
        assertTrue(beforeCompactStats.body().contains("\"sstableCount\":2"));

        // 3. POST /api/compact -> 204 No Content
        HttpRequest compactRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/compact"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> compactResponse = httpClient.send(compactRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, compactResponse.statusCode());

        // 4. Verify sstables merged into 1
        HttpResponse<String> afterCompactStats = httpClient.send(getStatsRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, afterCompactStats.statusCode());
        assertTrue(afterCompactStats.body().contains("\"sstableCount\":1"));

        // 5. Verify keys remain readable
        HttpRequest getK1 = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/entries/k1")).GET().build();
        assertEquals("v1", httpClient.send(getK1, HttpResponse.BodyHandlers.ofString()).body());

        HttpRequest getK2 = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/entries/k2")).GET().build();
        assertEquals("v2", httpClient.send(getK2, HttpResponse.BodyHandlers.ofString()).body());
    }
}
