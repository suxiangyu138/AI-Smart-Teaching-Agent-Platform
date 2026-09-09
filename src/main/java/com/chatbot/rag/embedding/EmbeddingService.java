package com.chatbot.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量嵌入服务 — 调用大模型 Embedding API 生成文本向量
 * <p>
 * 支持双模式：
 * 1. 云端模式：调用 DeepSeek/其他厂商的 Embedding API
 * 2. 本地模式：基于字符 n-gram TF-IDF 的轻量向量（无需联网，无需 API Key）
 *
 * @author suxiangyu
 */
@Service
public class EmbeddingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 本地模式向量维度 */
    private static final int LOCAL_EMBEDDING_DIM = 256;
    /** HTTP 成功状态码 */
    private static final int HTTP_OK = 200;
    /** n-gram 权重 */
    private static final float NGRAM_WEIGHT = 1.0f;
    /** 词汇特征权重 */
    private static final float WORD_WEIGHT = 2.0f;

    /**
     * 云端模式：调用 Embedding API 生成向量
     */
    public float[] embedCloud(String text, String apiKey, String baseUrl) throws Exception {
        String url = (baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.deepseek.com/v1")
                + "/embeddings";

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "text-embedding-ada-002");
        root.put("input", text);
        root.put("encoding_format", "float");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(root), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != HTTP_OK) {
            throw new RuntimeException("Embedding API 调用失败 HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        JsonNode data = MAPPER.readTree(response.body());
        JsonNode embedding = data.path("data").get(0).path("embedding");
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        return vector;
    }

    /**
     * 本地模式：基于字符 n-gram 生成简单向量
     * <p>
     * 优点：无需 API Key、无需联网、速度极快
     */
    public float[] embedLocal(String text) {
        float[] vector = new float[LOCAL_EMBEDDING_DIM];
        if (text == null || text.isEmpty()) {
            return vector;
        }

        String normalized = text.toLowerCase().replaceAll("\\s+", " ");
        for (int i = 0; i < normalized.length() - 1; i++) {
            int hash = Math.abs((normalized.charAt(i) * 31 + normalized.charAt(i + 1))
                    % LOCAL_EMBEDDING_DIM);
            vector[hash] += NGRAM_WEIGHT;
        }

        // 三元组特征：对中文词边界更敏感，提升本地向量区分度
        for (int i = 0; i < normalized.length() - 2; i++) {
            int hash = Math.abs((normalized.charAt(i) * 961 + normalized.charAt(i + 1) * 31
                    + normalized.charAt(i + 2)) % LOCAL_EMBEDDING_DIM);
            vector[hash] += NGRAM_WEIGHT;
        }

        String[] words = normalized.split("[\\s，。；：！？、\\(\\)\\[\\]{}]+");
        for (String word : words) {
            if (word.length() >= 2) {
                int hash = Math.abs(word.hashCode() % LOCAL_EMBEDDING_DIM);
                vector[hash] += WORD_WEIGHT;
            }
        }

        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    /**
     * 批量嵌入（云端模式）
     */
    public List<float[]> embedBatchCloud(List<String> texts, String apiKey, String baseUrl) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            try {
                results.add(embedCloud(text, apiKey, baseUrl));
            } catch (Exception e) {
                System.err.println("Embedding 云端调用失败，降级到本地模式: " + e.getMessage());
                results.add(embedLocal(text));
            }
        }
        return results;
    }

    /**
     * 批量嵌入（自动选择模式）
     */
    public List<float[]> embedBatch(List<String> texts, String apiKey, String baseUrl) {
        if (apiKey != null && !apiKey.isBlank()) {
            return embedBatchCloud(texts, apiKey, baseUrl);
        }
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(embedLocal(text));
        }
        return results;
    }
}
