package com.chatbot.rag;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.document.MathChunkingStrategy;
import com.chatbot.rag.document.PdfDocumentParser;
import com.chatbot.rag.embedding.EmbeddingService;
import com.chatbot.rag.model.DocumentChunk;
import com.chatbot.rag.model.KnowledgeBaseStats;
import com.chatbot.rag.model.SearchResult;
import com.chatbot.rag.vector.InMemoryVectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成核心服务
 * <p>
 * 编排 PDF 解析 → 文档分块 → 向量嵌入 → 入库 → 检索 → 上下文增强 全流程
 *
 * @author suxiangyu
 */
@Service
public class RagService {

    private static final Path KB_DIR = Paths.get(
            System.getProperty("user.home"), ".deepseek-chatbot", "knowledge_base");

    private final PdfDocumentParser pdfParser;
    private final MathChunkingStrategy chunkingStrategy;
    private final EmbeddingService embeddingService;
    private final InMemoryVectorStore vectorStore;

    /** 记录最近构建时间 */
    private String lastBuildTime;

    public RagService(PdfDocumentParser pdfParser,
                      MathChunkingStrategy chunkingStrategy,
                      EmbeddingService embeddingService,
                      InMemoryVectorStore vectorStore) {
        this.pdfParser = pdfParser;
        this.chunkingStrategy = chunkingStrategy;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;

        // 启动时尝试加载已有向量库
        try {
            vectorStore.load();
            System.out.println("[RAG] 向量库已加载，共 " + vectorStore.size() + " 条记录");
        } catch (IOException e) {
            System.err.println("[RAG] 向量库加载失败: " + e.getMessage());
        }
    }

    /**
     * 索引单个 PDF 文件
     *
     * @param pdfPath PDF 文件路径
     * @param grade   学段（junior/senior，null=自动识别）
     * @param apiKey  Embedding API Key（null=本地模式）
     * @param baseUrl Embedding API Base URL
     * @return 索引的切片数
     */
    public int indexPdf(Path pdfPath, String grade, String apiKey,
                         String baseUrl) throws IOException {
        String fileName = pdfPath.getFileName().toString();
        System.out.println("[RAG] 开始索引: " + fileName);

        // 1. 解析 PDF
        String text = pdfParser.parsePdf(pdfPath);
        if (text.isBlank()) {
            throw new IOException("PDF 无可提取文本: " + fileName);
        }

        // 2. 数学分块
        List<DocumentChunk> chunks = chunkingStrategy.chunkDocument(
                text, fileName, grade);
        if (chunks.isEmpty()) {
            throw new IOException("分块结果为空: " + fileName);
        }

        // 3. 向量嵌入
        List<String> chunkTexts = chunks.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());
        List<float[]> embeddings = embeddingService.embedBatch(
                chunkTexts, apiKey, baseUrl);

        // 4. 入库
        vectorStore.indexDocuments(chunks, embeddings, fileName);

        // 5. 持久化
        vectorStore.persist();
        System.out.println("[RAG] 索引完成: " + fileName + " → " + chunks.size() + " 块");
        return chunks.size();
    }

    /**
     * 批量索引目录下所有 PDF
     *
     * @param dirPath 目录路径
     * @param grade   学段
     * @param apiKey  API Key
     * @param baseUrl Base URL
     * @return 索引统计
     */
    public Map<String, Integer> indexDirectory(Path dirPath, String grade,
                                                String apiKey, String baseUrl) throws IOException {
        if (!Files.isDirectory(dirPath)) {
            throw new IOException("路径不存在或不是目录: " + dirPath);
        }

        Map<String, Integer> stats = new LinkedHashMap<>();
        try (var files = Files.list(dirPath)) {
            List<Path> pdfFiles = files
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
            for (Path pdf : pdfFiles) {
                try {
                    int count = indexPdf(pdf, grade, apiKey, baseUrl);
                    stats.put(pdf.getFileName().toString(), count);
                } catch (Exception e) {
                    System.err.println("[RAG] 索引失败 " + pdf.getFileName() + ": " + e.getMessage());
                    stats.put(pdf.getFileName().toString(), -1);
                }
            }
        }
        lastBuildTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return stats;
    }

    /**
     * RAG 检索 + 增强 Prompt 构建
     * <p>
     * 完整流程：用户提问 → 向量/关键词检索 → 拼接上下文 → 返回增强后的 prompt
     *
     * @param userQuery 用户问题
     * @param req       原始请求（含学段、API Key 等信息）
     * @param topK      检索数量
     * @return 增强后的系统提示词（可直接拼接到 messages）
     */
    public String buildRagContext(String userQuery, UnifiedChatRequest req, int topK) {
        if (vectorStore.size() == 0) {
            return "";
        }

        String stage = req.getStage() != null ? req.getStage() : UnifiedChatRequest.STAGE_JUNIOR;
        boolean allowExtend = req.isAllowUniversityExtend();

        List<SearchResult> results;

        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            try {
                float[] queryEmbedding = embeddingService.embedCloud(
                        userQuery, req.getApiKey(), req.getBaseUrl());
                results = vectorStore.search(queryEmbedding, topK,
                        stage, allowExtend);
            } catch (Exception e) {
                results = vectorStore.keywordSearch(userQuery, topK,
                        stage, allowExtend);
            }
        } else {
            results = vectorStore.keywordSearch(userQuery, topK,
                    stage, allowExtend);
        }

        results = results.stream()
                .filter(r -> r.getScore() > 0)
                .collect(Collectors.toList());

        if (results.isEmpty()) {
            return "";
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("\n\n---\n");
        ctx.append("【知识库参考资料】以下是从教材/题库中检索到的相关内容，请优先参考：\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String chunkStage = r.getChunk().getStage() != null
                    ? r.getChunk().getStage() : "unknown";
            String stageLabel = buildStageLabel(chunkStage);

            ctx.append("📖 参考").append(i + 1).append(" ");
            ctx.append(stageLabel).append(" ");
            if (r.getChunk().getChapterTitle() != null
                    && !r.getChunk().getChapterTitle().isEmpty()) {
                ctx.append("（").append(r.getChunk().getChapterTitle()).append("）");
            }
            ctx.append(" [来源: ").append(r.getSourceFile()).append("]");
            ctx.append(" [匹配度: ").append(String.format("%.0f%%", r.getScore() * 100))
                    .append("]\n");
            ctx.append(r.getChunk().getContent()).append("\n\n");
        }

        ctx.append("请基于以上教材内容，结合你的数学知识，回答用户问题。");
        ctx.append("如果引用了教材原文，请标注出处和所属学段。\n");
        ctx.append("---\n");

        return ctx.toString();
    }

    /** 学段 → 用户可读标签 */
    private String buildStageLabel(String stage) {
        if (UnifiedChatRequest.STAGE_PRIMARY.equals(stage)) {
            return "🏫小学";
        }
        if (UnifiedChatRequest.STAGE_JUNIOR.equals(stage)) {
            return "🏫初中";
        }
        if (UnifiedChatRequest.STAGE_SENIOR.equals(stage)) {
            return "🎓高中";
        }
        if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(stage)) {
            return "📚大学拓展";
        }
        return "";
    }

    /**
     * 获取知识库统计
     */
    public KnowledgeBaseStats getStats() {
        KnowledgeBaseStats stats = new KnowledgeBaseStats();
        stats.setTotalDocuments(vectorStore.indexedFileCount());
        stats.setTotalChunks(vectorStore.size());
        stats.setTotalChars(vectorStore.totalChars());

        Map<String, Integer> byGrade = vectorStore.countByGrade();
        stats.setJuniorCount(byGrade.getOrDefault("junior", 0));
        stats.setSeniorCount(byGrade.getOrDefault("senior", 0));
        stats.setDiskSizeBytes(vectorStore.diskSizeBytes());
        stats.setLastIndexedTime(lastBuildTime);
        return stats;
    }

    /**
     * 获取知识库目录
     */
    public Path getKbDir() {
        try {
            Files.createDirectories(KB_DIR);
        } catch (IOException ignored) {}
        return KB_DIR;
    }

    /**
     * 删除指定文档的索引
     */
    public void removeDocument(String fileName) {
        vectorStore.removeBySource(fileName);
        try {
            vectorStore.persist();
        } catch (IOException e) {
            System.err.println("[RAG] 持久化失败: " + e.getMessage());
        }
    }

    /**
     * 重建全部索引
     */
    public void clearAll() {
        vectorStore.clear();
        try {
            vectorStore.persist();
        } catch (IOException e) {
            System.err.println("[RAG] 持久化失败: " + e.getMessage());
        }
    }
}
