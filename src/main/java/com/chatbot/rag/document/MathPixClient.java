package com.chatbot.rag.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * MathPix API 客户端 — 数学公式 + 文字识别（业界最强）
 * <p>
 * 注册: https://mathpix.com/  → 获取 app_id + app_key
 * 免费额度: 1000次/月
 *
 * @author suxiangyu
 */
@Service
public class MathPixClient {

    private static final String API_URL = "https://api.mathpix.com/v3/text";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private String appId;
    private String appKey;
    private Boolean available;

    /** 配置 MathPix 凭据 */
    public void configure(String appId, String appKey) {
        this.appId = appId;
        this.appKey = appKey;
        this.available = null;
    }

    /** 是否已配置且可用 */
    public boolean isAvailable() {
        if (available != null) {
            return available;
        }
        available = appId != null && !appId.isBlank()
                && appKey != null && !appKey.isBlank();
        return available;
    }

    /**
     * 识别单页图片 — 返回 Markdown + LaTeX 混合文本
     */
    public String recognizePage(byte[] imageBytes) throws IOException, InterruptedException {
        if (!isAvailable()) {
            throw new IOException("MathPix 未配置 API Key");
        }

        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String jsonBody = String.format(
                "{\"src\":\"data:image/png;base64,%s\",\"formats\":[\"text\",\"latex_styled\"],"
                        + "\"format_options\":{\"latex_styled\":{\"transforms\":[\"math_simplified\"]}},"
                        + "\"ocr\":[\"math\",\"text\"],\"skip_recrop\":true}",
                base64);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("app_id", appId)
                .header("app_key", appKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new IOException("MathPix API Key 无效");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("MathPix 返回 HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode json = MAPPER.readTree(resp.body());
        String text = json.path("text").asText("");

        if (text.isEmpty()) {
            // 尝试 latex_styled 格式
            text = json.path("latex_styled").asText("");
        }
        return text;
    }
}
