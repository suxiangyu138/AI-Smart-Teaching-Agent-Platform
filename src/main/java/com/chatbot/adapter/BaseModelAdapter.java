package com.chatbot.adapter;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.model.UnifiedStreamChunk;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 模型适配器抽象基类 — 子类只需定义厂商特定的请求体转换与端点
 *
 * @author suxiangyu
 */
public abstract class BaseModelAdapter {

    private static final int HTTP_OK = 200;
    private static final ThreadPoolExecutor EXECUTOR =
            new ThreadPoolExecutor(0, 8, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "adapter-stream");
                        t.setDaemon(true);
                        return t;
                    });

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * 返回厂商标识。
     *
     * @return deepseek / zhipu / qwen / moonshot / minimax / mimo
     */
    public abstract String getProviderCode();

    /**
     * 将统一请求转为厂商原生 JSON 请求体
     *
     * @param req 统一请求
     * @return 厂商原生 JSON 字符串
     * @throws Exception 序列化异常
     */
    protected abstract String buildNativeBody(
            UnifiedChatRequest req) throws Exception;

    /**
     * 流式调用厂商 API，通过回调输出统一 StreamChunk
     *
     * @param req        统一请求
     * @param onChunk    收到片段时回调
     * @param onError    出错时回调
     * @param onComplete 完成时回调
     */
    public void streamChat(UnifiedChatRequest req,
                           Consumer<UnifiedStreamChunk> onChunk,
                           Consumer<Throwable> onError,
                           Runnable onComplete) {
        EXECUTOR.submit(() -> {
            try {
                String body = buildNativeBody(req);
                String url = buildUrl(req);

                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization",
                                "Bearer " + req.getApiKey())
                        .timeout(Duration.ofSeconds(180))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<java.io.InputStream> resp =
                        HTTP.send(httpReq,
                                HttpResponse.BodyHandlers.ofInputStream());

                if (resp.statusCode() != HTTP_OK) {
                    byte[] err = resp.body().readAllBytes();
                    onError.accept(new RuntimeException(
                            "HTTP " + resp.statusCode() + ": "
                                    + new String(err, StandardCharsets.UTF_8)));
                    return;
                }

                StringBuilder full = new StringBuilder();
                StringBuilder fullThink = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(resp.body(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String d = line.substring(6).trim();
                            if ("[DONE]".equals(d)) {
                                break;
                            }
                            try {
                                // 分别提取思考过程和最终回答
                                String reasoning = extractReasoning(d);
                                if (reasoning != null && !reasoning.isEmpty()) {
                                    fullThink.append(reasoning);
                                    onChunk.accept(UnifiedStreamChunk.reasoning(
                                            reasoning, fullThink.toString()));
                                }
                                String content = extractContent(d);
                                if (content != null && !content.isEmpty()) {
                                    full.append(content);
                                    onChunk.accept(UnifiedStreamChunk.chunk(
                                            content, full.toString()));
                                }
                            } catch (Exception ignored) {
                                // 跳过非 JSON 行
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

    private String buildUrl(UnifiedChatRequest req) {
        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            String base = req.getBaseUrl().trim();
            // 只接受 http/https。baseUrl 由请求方提供，属于不可信输入，
            // 限制协议可避免 file: 之类的 scheme 被带进 HTTP 客户端。
            // 注意：这里刻意不封禁内网/回环地址——本地 Ollama、LM Studio
            // 等自建模型服务依赖该能力。
            String lower = base.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "baseUrl 必须以 http:// 或 https:// 开头");
            }
            // 去掉结尾斜杠，避免拼接出 //chat/completions
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/chat/completions";
        }
        return "https://api.deepseek.com/v1/chat/completions";
    }

    /** 从 SSE data JSON 提取 content（最终回答） */
    protected String extractContent(String data) throws Exception {
        var n = MAPPER.readTree(data);
        var choices = n.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            var delta = choices.get(0).get("delta");
            if (delta != null) {
                var c = delta.get("content");
                if (c != null && !c.isNull() && !c.asText().isEmpty()) {
                    return c.asText();
                }
            }
        }
        return null;
    }

    /** 从 SSE data JSON 提取 reasoning_content（思考/推理过程） */
    protected String extractReasoning(String data) throws Exception {
        var n = MAPPER.readTree(data);
        var choices = n.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            var delta = choices.get(0).get("delta");
            if (delta != null) {
                var rc = delta.get("reasoning_content");
                if (rc != null && !rc.isNull() && !rc.asText().isEmpty()) {
                    return rc.asText();
                }
            }
        }
        return null;
    }
}
