package com.ticketbooking.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Thread-safe HTTP client used by integration and concurrency tests against the real server. */
public class ApiClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public ApiClient(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    public Response get(String path, String token) {
        return send("GET", path, token, null, Map.of());
    }

    public Response post(String path, String token, Object body) {
        return send("POST", path, token, body, Map.of());
    }

    public Response post(String path, String token, Object body, Map<String, String> headers) {
        return send("POST", path, token, body, headers);
    }

    public Response delete(String path, String token) {
        return send("DELETE", path, token, null, Map.of());
    }

    public Response send(String method, String path, String token, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            headers.forEach(builder::header);
            if (body != null) {
                String json = body instanceof String s ? s : mapper.writeValueAsString(body);
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(json));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body() == null || response.body().isBlank()
                    ? mapper.nullNode() : mapper.readTree(response.body());
            return new Response(response.statusCode(), json, response.headers());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record Response(int status, JsonNode body, HttpHeaders headers) {
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String code() {
            return body.path("code").asText(null);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }
}
