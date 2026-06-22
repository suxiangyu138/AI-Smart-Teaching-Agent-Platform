package com.chatbot.rag.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Playwright 渲染服务客户端（JS 动态页面回退方案）
 */
@Service
public class PlaywrightClient {

    private static final String RENDER_URL = "http://127.0.0.1:8002/render";
    private static final String HEALTH_URL = "http://127.0.0.1:8002/health";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(HEALTH_URL))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 渲染 JS 页面，返回完整 HTML 文本 */
    public String render(String url, String waitSelector, int scrollTimes) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("url", url);
        if (waitSelector != null) body.put("wait_selector", waitSelector);
        body.put("scroll_times", Math.max(0, scrollTimes));
        body.put("timeout_ms", 25000);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RENDER_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(resp.body());
            if (json.path("success").asBoolean()) {
                return json.path("html").asText();
            }
            throw new IOException("Playwright render failed: " + json.path("error").asText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Playwright interrupted", e);
        }
    }
}
