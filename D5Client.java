package com.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to D5's REST API (/api/users). The token stays on the server; the browser never sees it.
 * App settings: D5_BASE_URL (e.g. https://abhishekk.azurewebsites.net) and D5_TOKEN (the plain token).
 */
final class D5Client {

    record Response(int status, String body) { }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private D5Client() { }

    static Response get(String path) throws IOException, InterruptedException {
        return send(builder(path).GET().build());
    }

    static Response delete(String path) throws IOException, InterruptedException {
        return send(builder(path).DELETE().build());
    }

    private static HttpRequest.Builder builder(String path) {
        String base = env("D5_BASE_URL");
        String token = env("D5_TOKEN");
        if (base == null || token == null) {
            throw new IllegalStateException("D5_BASE_URL and D5_TOKEN application settings are required");
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))   // free-tier database may need time to resume
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private static Response send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body());
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
