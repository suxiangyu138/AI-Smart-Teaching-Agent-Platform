package com.chatbot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DeepSeek API 客户端，支持普通请求和流式输出
 *
 * @author suxiangyu
 */
public class DeepSeekClient {

    /** HTTP 成功状态码 */
    private static final int HTTP_OK = 200;

    private final ConfigManager config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor streamExecutor;

    public DeepSeekClient(ConfigManager config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
        this.streamExecutor = new ThreadPoolExecutor(
                0, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "deepseek-stream");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 发送聊天请求（非流式，返回完整回复）
     */
    public String chat(List<ChatMessage> messages)
            throws IOException, InterruptedException {
        String requestBody = buildRequestBody(messages, false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getApiEndpoint()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != HTTP_OK) {
            throw new IOException("API请求失败 (HTTP "
                    + response.statusCode() + "): " + response.body());
        }

        return parseResponse(response.body());
    }

    /**
     * 发送聊天请求（流式输出，实时回调）
     *
     * @param messages   对话历史
     * @param onChunk    每收到一段文本时回调
     * @param onError    出错时回调
     * @param onComplete 完成时回调
     */
    public void chatStream(List<ChatMessage> messages,
                           Consumer<String> onChunk,
                           Consumer<Throwable> onError,
                           Runnable onComplete) {
        streamExecutor.submit(() -> {
            try {
                String requestBody = buildRequestBody(messages, true);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.getApiEndpoint()))
                        .header("Content-Type", "application/json")
                        .header("Authorization",
                                "Bearer " + config.getApiKey())
                        .timeout(Duration.ofSeconds(180))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                requestBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != HTTP_OK) {
                    byte[] errorBytes = response.body().readAllBytes();
                    String errorBody = new String(errorBytes,
                            StandardCharsets.UTF_8);
                    throw new IOException("API请求失败 (HTTP "
                            + response.statusCode() + "): " + errorBody);
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            try {
                                String content = extractDeltaText(data);
                                if (content != null) {
                                    onChunk.accept(content);
                                }
                            } catch (JsonProcessingException e) {
                                // 非 JSON 行跳过
                            }
                        }
                    }
                }
                onComplete.run();

            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    /**
     * 从 SSE delta 中提取文本内容。
     * 同时兼容 deepseek-chat（content）和
     * deepseek-reasoner（reasoning_content → content）。
     */
    private String extractDeltaText(String data)
            throws JsonProcessingException {
        var node = mapper.readTree(data);
        var choices = node.get("choices");
        if (choices == null || !choices.isArray()
                || choices.isEmpty()) {
            return null;
        }
        var delta = choices.get(0).get("delta");
        if (delta == null) {
            return null;
        }
        // 优先取 content
        var content = delta.get("content");
        if (content != null && !content.isNull()
                && !content.asText().isEmpty()) {
            return content.asText();
        }
        // deepseek-reasoner 思考阶段的 reasoning_content
        var reasoning = delta.get("reasoning_content");
        if (reasoning != null && !reasoning.isNull()
                && !reasoning.asText().isEmpty()) {
            return reasoning.asText();
        }
        return null;
    }

    /**
     * 构建请求体 JSON
     */
    private String buildRequestBody(List<ChatMessage> messages,
                                     boolean stream)
            throws JsonProcessingException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.getModel());
        root.put("stream", stream);
        root.put("temperature", 0.7);
        root.put("max_tokens", 4096);

        ArrayNode msgs = mapper.createArrayNode();

        // 添加系统提示
        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", config.getSystemPrompt());
        msgs.add(sysMsg);

        // 添加对话历史
        for (ChatMessage msg : messages) {
            ObjectNode m = mapper.createObjectNode();
            String role = msg.getRole() == ChatMessage.Role.USER
                    ? "user" : "assistant";
            m.put("role", role);
            m.put("content", msg.getContent());
            msgs.add(m);
        }

        root.set("messages", msgs);
        return mapper.writeValueAsString(root);
    }

    /**
     * 解析非流式响应
     */
    private String parseResponse(String body)
            throws JsonProcessingException {
        var node = mapper.readTree(body);
        var choices = node.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            var message = choices.get(0).get("message");
            if (message != null) {
                var content = message.get("content");
                if (content != null && !content.isNull()) {
                    return content.asText();
                }
            }
        }
        throw new JsonProcessingException("无法解析API响应: " + body) {};
    }
}
