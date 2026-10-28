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
        engine = new MiniLsmEngine(walPath);

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
}
