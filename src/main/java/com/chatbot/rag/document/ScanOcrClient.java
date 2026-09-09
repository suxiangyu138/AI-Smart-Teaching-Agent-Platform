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

/**
 * 扫描 PDF OCR 识别客户端
 * <p>
 * 调用 Python OCR 微服务（127.0.0.1:8001）识别扫描图片页
 *
 * @author suxiangyu
 */
@Service
public class ScanOcrClient {

    private static final String OCR_URL = "http://127.0.0.1:8001/ocr/page";
    private static final String HEALTH_URL = "http://127.0.0.1:8001/health";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            // 强制 HTTP/1.1：uvicorn 无法解析 Java 默认的 h2c 升级探测，会导致请求体丢失（empty body）
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 检查 OCR 服务是否在线（每次都真实检测，不缓存）
     */
    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(HEALTH_URL))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            boolean ok = resp.statusCode() == 200;
            System.out.println("[OCR] Health check: " + ok);
            return ok;
        } catch (Exception e) {
            System.err.println("[OCR] Health check FAILED: " + e.getMessage());
            return false;
        }
    }

    /**
     * 识别单页扫描图片，返回带 LaTeX 的文本
     *
     * @param imageBytes 图片二进制数据（PNG/JPEG）
     * @return OCR 识别结果文本（含 $$公式$$）
     */
    public String recognizePage(byte[] imageBytes) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OCR_URL))
                .header("Content-Type", "image/png")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .build();

        HttpResponse<String> resp = HTTP.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() != 200) {
            throw new IOException("OCR 服务返回 HTTP " + resp.statusCode());
        }

        JsonNode json = MAPPER.readTree(resp.body());
        boolean success = json.path("success").asBoolean(false);
        if (!success) {
            String err = json.path("error").asText("未知错误");
            throw new IOException("OCR 识别失败: " + err);
        }
        return json.path("page_content").asText("");
    }
}
