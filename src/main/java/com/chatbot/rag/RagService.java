package com.chatbot.rag;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.document.MathChunkingStrategy;
import com.chatbot.rag.document.PdfDocumentParser;
import com.chatbot.rag.embedding.EmbeddingService;
import com.chatbot.rag.model.DocumentChunk;
import com.chatbot.rag.model.KnowledgeBaseStats;
import com.chatbot.rag.model.SearchResult;
import com.chatbot.rag.model.VectorDocument;
import com.chatbot.rag.vector.LuceneVectorStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成核心服务
 * <p>
 * 企业特性：
 * - 断点续存（processed_files.json）
 * - 内容哈希去重
 * - 失败文件追踪
 * - 增量导入
 *
 * @author suxiangyu
 */
@Service
public class RagService {

    private static final Path KB_DIR = Paths.get(
            System.getProperty("user.dir"), "data", "knowledge_base");
    // Lucene 引擎的断点文件独立命名：旧引擎（JSON 向量库）的断点不再适用，首次启动自动全量重建
    private static final Path CHECKPOINT_FILE = Paths.get(
            System.getProperty("user.dir"), "data", "processed_files_lucene.json");
    private static final Path FAILED_FILE = Paths.get(
            System.getProperty("user.dir"), "data", "failed_files_lucene.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PdfDocumentParser pdfParser;
    private final MathChunkingStrategy chunkingStrategy;
    private final EmbeddingService embeddingService;
    private final LuceneVectorStore vectorStore;

    private String lastBuildTime;
    private int progressCurrent;
    private int progressTotal;
    private String progressStage = "";

    /** 已处理文件清单（断点续存） */
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();
    /** 失败文件清单 */
    private final Map<String, String> failedFiles = new ConcurrentHashMap<>();

    public RagService(PdfDocumentParser pdfParser,
                      MathChunkingStrategy chunkingStrategy,
                      EmbeddingService embeddingService,
                      LuceneVectorStore vectorStore) {
        this.pdfParser = pdfParser;
        this.chunkingStrategy = chunkingStrategy;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;

        // 启动加载（Lucene 索引在存储引擎构造时完成加载）
        loadCheckpoint();
        loadFailedFiles();
        System.out.println("[RAG] 已加载 " + vectorStore.size() + " 条记录, "
                + processedFiles.size() + " 个已处理文件");
    }

    // ================================
    //  单文件索引
    // ================================

    public int indexPdf(Path pdfPath, String stage, String apiKey,
                         String baseUrl) throws IOException {
        String absPath = pdfPath.toRealPath().toString();
        String fileName = pdfPath.getFileName().toString();

        // 断点续存：跳过已处理
        if (processedFiles.contains(absPath)) {
            System.out.println("[RAG] 跳过已处理: " + fileName);
            return 0;
        }

        System.out.println("[RAG] 开始索引: " + fileName);

        // 解析 PDF
        String text = pdfParser.parsePdf(pdfPath);
        if (text.isBlank()) {
            failedFiles.put(fileName, "无可提取文本或扫描版PDF");
            saveFailedFiles();
            processedFiles.add(absPath);
            saveCheckpoint();
            throw new IOException("PDF 无可提取文本: " + fileName);
        }

        // 分块 + 去重
        List<DocumentChunk> chunks = chunkingStrategy.chunkDocument(text, fileName, stage);
        chunks = dedupChunks(chunks);
        if (chunks.isEmpty()) {
            failedFiles.put(fileName, "分块结果为空");
            saveFailedFiles();
            processedFiles.add(absPath);
            saveCheckpoint();
            throw new IOException("分块结果为空: " + fileName);
        }

        // 向量嵌入：统一使用本地 n-gram 向量（Lucene 向量字段要求维度一致，云端嵌入维度不兼容）
        List<String> chunkTexts = chunks.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());
        List<float[]> embeddings = embeddingService.embedBatch(chunkTexts, null, null);

        // 入库
        vectorStore.indexDocuments(chunks, embeddings, fileName);

        // 持久化
        vectorStore.persist();
        processedFiles.add(absPath);
        saveCheckpoint();
        System.out.println("[RAG] 索引完成: " + fileName + " → " + chunks.size() + " 块");
        return chunks.size();
    }

    // ================================
    //  批量索引（断点续存 + 增量）
    // ================================

    public Map<String, Integer> indexDirectory(Path dirPath, String stage,
                                                String apiKey, String baseUrl) throws IOException {
        if (!Files.isDirectory(dirPath)) {
            throw new IOException("路径不存在或不是目录: " + dirPath);
        }

        Map<String, Integer> stats = new LinkedHashMap<>();
        List<Path> pdfFiles;

        try (var files = Files.list(dirPath)) {
            pdfFiles = files
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }

        // 统计增量文件数（未处理 + 失败的也重试）
        long newCount = pdfFiles.stream()
                .filter(p -> {
                    try {
                        String abs = p.toRealPath().toString();
                        return !processedFiles.contains(abs)
                                || failedFiles.containsKey(p.getFileName().toString());
                    } catch (IOException e) {
                        return false;
                    }
                }).count();

        progressTotal = Math.max(1, (int) newCount);
        progressCurrent = 0;
        progressStage = "开始批量索引（增量 " + newCount + "/" + pdfFiles.size() + " 个）...";

        for (Path pdf : pdfFiles) {
            String fileName = pdf.getFileName().toString();
            // 跳过上传重名产生的副本（xxx_<13位时间戳>.pdf，且同名基础文件存在、大小一致）
            if (isTimestampedDuplicate(pdf, fileName)) {
                stats.put(fileName, 0);
                continue;
            }
            // 跳过已成功处理的；失败的清除断点标记，允许真正重试
            try {
                String abs = pdf.toRealPath().toString();
                if (processedFiles.contains(abs)) {
                    if (failedFiles.containsKey(fileName)) {
                        processedFiles.remove(abs);
                        saveCheckpoint();
                    } else {
                        // 0 = 已跳过
                        stats.put(fileName, 0);
                        continue;
                    }
                }
            } catch (IOException ignored) {}

            progressCurrent++;
            progressStage = "正在索引: " + fileName;
            try {
                // 学段：优先显式指定，否则按文件名逐文件自动识别
                String fileStage = (stage != null && !stage.isBlank())
                        ? stage : detectStageFromFileName(fileName);
                int count = indexPdf(pdf, fileStage, apiKey, baseUrl);
                stats.put(fileName, count);
                failedFiles.remove(fileName);
                saveFailedFiles();
            } catch (Exception e) {
                System.err.println("[RAG] 索引失败 " + fileName + ": " + e.getMessage());
                e.printStackTrace();
                stats.put(fileName, -1);
                // e.getMessage() 可能为 null，ConcurrentHashMap 不允许 null 值
                failedFiles.put(fileName, e.getMessage() != null
                        ? e.getMessage() : e.getClass().getSimpleName());
                saveFailedFiles();
                try {
                    String abs = pdf.toRealPath().toString();
                    processedFiles.add(abs);
                    saveCheckpoint();
                } catch (IOException ignored) {}
            }
            // 每完成一个文件持久化（单独保护，持久化异常不中断整批）
            try {
                vectorStore.persist();
            } catch (IOException e) {
                System.err.println("[RAG] 持久化失败: " + e.getMessage());
            }
        }
        progressStage = "索引完成";
        lastBuildTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return stats;
    }

    // ================================
    //  RAG 检索
    // ================================

    public String buildRagContext(String userQuery, UnifiedChatRequest req, int topK) {
        if (vectorStore.size() == 0) {
            return "";
        }
        String stage = req.getStage() != null ? req.getStage() : UnifiedChatRequest.STAGE_JUNIOR;
        boolean allowExtend = req.isAllowUniversityExtend();

        // 查询语义增强：追加学段限定词；统一使用本地向量（与索引向量维度一致）
        String enhancedQuery = vectorStore.enhanceQuery(userQuery, stage);
        float[] qEmb = embeddingService.embedLocal(enhancedQuery);
        List<SearchResult> results =
                vectorStore.searchMultiRecall(qEmb, enhancedQuery, topK, stage, allowExtend);

        results = results.stream().filter(r -> r.getScore() > 0).collect(Collectors.toList());
        if (results.isEmpty()) {
            return "";
        }

        // 按权重和得分排序
        results.sort((a, b) -> {
            double aScore = a.getScore() * (a.getChunk().getWeight() > 0 ? a.getChunk().getWeight() : 1);
            double bScore = b.getScore() * (b.getChunk().getWeight() > 0 ? b.getChunk().getWeight() : 1);
            return Double.compare(bScore, aScore);
        });

        // 上下文扩展：为高相关分块附带同文档前后相邻分块，保证推导上下文完整
        results = expandContext(results, Math.max(topK, 4) + 4);

        StringBuilder ctx = new StringBuilder();
        ctx.append("\n\n---\n");
        ctx.append("【以下为对应学段官方教材、教辅参考资料】\n");
        ctx.append("解题必须严格依据下面内容，禁止编造定理、公式、解题步骤。\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String chunkStage = r.getChunk().getStage() != null ? r.getChunk().getStage() : "unknown";
            ctx.append("参考").append(i + 1).append(" ");
            ctx.append(buildStageLabel(chunkStage)).append(" ");

            // 来源类型标签
            String srcType = r.getChunk().getSourceType();
            if ("textbook".equals(srcType)) {
                ctx.append("教材 ");
            } else if ("exam".equals(srcType)) {
                ctx.append("真题 ");
            } else if ("crawl".equals(srcType)) {
                ctx.append("网络 ");
            }

            if (r.getChunk().getChapterTitle() != null && !r.getChunk().getChapterTitle().isEmpty()) {
                ctx.append("（").append(r.getChunk().getChapterTitle()).append("）");
            }

            // 溯源：文件名/书名 + 页码
            ctx.append("[");
            if (r.getChunk().getSourceName() != null && !r.getChunk().getSourceName().isEmpty()) {
                ctx.append(r.getChunk().getSourceName());
            } else {
                ctx.append(r.getSourceFile());
            }
            if (r.getChunk().getPageNum() != null) {
                ctx.append(" P").append(r.getChunk().getPageNum());
            }
            ctx.append("]");
            ctx.append(" [得分: ").append(String.format("%.0f%%", r.getScore() * 100)).append("]\n");
            // 安全兜底：若内容含裸 LaTeX 命令，自动包裹 $$...$$
            String chunkContent = r.getChunk().getContent();
            if (com.chatbot.rag.crawler.FormulaNormalizer.hasLatexCommands(chunkContent)) {
                chunkContent = com.chatbot.rag.crawler.FormulaNormalizer.normalizeBatch(chunkContent);
            }
            // 剥离混入的 HTML 标记（爬虫来源可能携带渲染后的 KaTeX/MathJax HTML），
            // 避免模型把标签原样复述到回答里
            chunkContent = stripHtml(chunkContent);
            ctx.append(chunkContent).append("\n\n");
        }

        ctx.append("请基于以上教材内容，结合数学知识回答。用到的定理/公式标注出处。");
        ctx.append("若无相关知识库内容，如实告知「暂无此知识点资料」。\n---\n");
        return ctx.toString();
    }

    /**
     * 上下文扩展：为每个检索结果附带同文档的前后相邻分块（去重、限量）。
     * 相邻分块按锚点得分的 0.9 倍计分，保持原有排序结构。
     */
    private List<SearchResult> expandContext(List<SearchResult> results, int maxChunks) {
        if (results.isEmpty() || results.size() >= maxChunks) {
            return results;
        }
        List<SearchResult> expanded = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SearchResult r : results) {
            if (expanded.size() >= maxChunks) {
                break;
            }
            if (!seen.add(r.getChunk().getChunkId())) {
                continue;
            }
            expanded.add(r);
            VectorDocument anchor = vectorStore.getById(r.getChunk().getChunkId());
            for (VectorDocument neighbor : vectorStore.adjacentChunks(anchor, 1, 1)) {
                if (expanded.size() >= maxChunks) {
                    break;
                }
                if (seen.add(neighbor.getId())) {
                    expanded.add(vectorStore.wrapResult(neighbor, r.getScore() * 0.9));
                }
            }
        }
        return expanded;
    }

    /** 剥离分块内容中混入的 HTML 标记（含实体转义形态的标签） */
    private static String stripHtml(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("(?s)<[^>]*>", "");
        cleaned = cleaned.replaceAll("(?s)&lt;/?[a-zA-Z][^&]*&gt;", "");
        return cleaned.trim();
    }

    /** 上传重名副本：xxx_<13位时间戳>.pdf 且同名基础文件存在且大小一致 → 跳过 */
    private boolean isTimestampedDuplicate(Path pdf, String fileName) {
        if (!fileName.matches(".*_\\d{13}\\.pdf")) {
            return false;
        }
        String baseName = fileName.replaceAll("_\\d{13}\\.pdf$", ".pdf");
        Path base = pdf.resolveSibling(baseName);
        if (!Files.exists(base)) {
            return false;
        }
        try {
            return Files.size(base) == Files.size(pdf);
        } catch (IOException e) {
            return false;
        }
    }

    /** 按文件名关键词检测学段（批量索引未显式指定学段时使用） */
    private String detectStageFromFileName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.contains("university") || lower.contains("大学") || lower.contains("高等")
                || lower.contains("微积分") || lower.contains("线性代数") || lower.contains("离散数学")
                || lower.contains("概率论") || lower.contains("数理统计") || lower.contains("数学建模")
                || lower.contains("数学竞赛") || lower.contains("数学分析") || lower.contains("考研")
                || lower.contains("组合数学") || lower.contains("图论") || lower.contains("数值分析")
                || lower.contains("应用数学") || lower.contains("复变函数") || lower.contains("常微分")
                || lower.contains("抽象代数") || lower.contains("泛函分析") || lower.contains("吉米多维奇")) {
            return UnifiedChatRequest.STAGE_UNIVERSITY;
        }
        if (lower.contains("senior") || lower.contains("高中") || lower.contains("高考")) {
            return UnifiedChatRequest.STAGE_SENIOR;
        }
        if (lower.contains("primary") || lower.contains("小学")) {
            return UnifiedChatRequest.STAGE_PRIMARY;
        }
        if (lower.contains("junior") || lower.contains("初中") || lower.contains("中考")) {
            return UnifiedChatRequest.STAGE_JUNIOR;
        }
        // 数学资料默认高中
        return UnifiedChatRequest.STAGE_SENIOR;
    }

    /** 检索调试：返回 top-K 结果（不调用 LLM，用于评估检索质量） */
    public List<SearchResult> searchDebug(String query, String stage,
                                          boolean allowExtend, int topK) {
        String enhanced = vectorStore.enhanceQuery(query, stage);
        float[] qEmb = embeddingService.embedLocal(enhanced);
        return vectorStore.searchMultiRecall(qEmb, enhanced, topK, stage, allowExtend);
    }

    /** 学段 → 用户可读标签 */
    private static final Map<String, String> STAGE_LABELS = Map.of(
            UnifiedChatRequest.STAGE_PRIMARY, "小学",
            UnifiedChatRequest.STAGE_JUNIOR, "初中",
            UnifiedChatRequest.STAGE_SENIOR, "高中",
            UnifiedChatRequest.STAGE_UNIVERSITY, "大学拓展"
    );

    private String buildStageLabel(String stage) {
        return STAGE_LABELS.getOrDefault(stage, "");
    }

    // ================================
    //  公式标准化与统计
    // ================================

    /**
     * 批量重新标准化所有文档中的公式格式。
     * 检测裸 LaTeX 命令，包裹为 $$...$$，更新元数据并持久化。
     */
    public Map<String, Object> restandardizeFormulas() {
        return vectorStore.normalizeFormulas();
    }

    /**
     * 获取公式统计信息
     */
    public Map<String, Object> getFormulaStats() {
        long totalDocs = vectorStore.size();
        long withFormulas = vectorStore.countDocumentsWithFormulas();
        long totalBlocks = vectorStore.countTotalFormulaBlocks();
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalDocuments", totalDocs);
        stats.put("documentsWithFormulas", withFormulas);
        stats.put("totalFormulaBlocks", totalBlocks);
        stats.put("hasFormulaRatio", totalDocs > 0
                ? String.format("%.1f%%", (double) withFormulas / totalDocs * 100) : "0.0%");
        return stats;
    }

    // ================================
    //  断点续存
    // ================================

    private void loadCheckpoint() {
        try {
            if (Files.exists(CHECKPOINT_FILE)) {
                List<String> files = MAPPER.readValue(CHECKPOINT_FILE.toFile(), new TypeReference<>() {});
                processedFiles.addAll(files);
            }
        } catch (IOException e) {
            System.err.println("[RAG] 加载断点失败: " + e.getMessage());
        }
    }

    private void saveCheckpoint() {
        try {
            Files.createDirectories(CHECKPOINT_FILE.getParent());
            MAPPER.writeValue(CHECKPOINT_FILE.toFile(), new ArrayList<>(processedFiles));
        } catch (IOException e) {
            System.err.println("[RAG] 保存断点失败: " + e.getMessage());
        }
    }

    private void loadFailedFiles() {
        try {
            if (Files.exists(FAILED_FILE)) {
                Map<String, String> m = MAPPER.readValue(FAILED_FILE.toFile(), new TypeReference<>() {});
                failedFiles.putAll(m);
            }
        } catch (IOException e) {
            System.err.println("[RAG] 加载失败清单失败: " + e.getMessage());
        }
    }

    private void saveFailedFiles() {
        try {
            Files.createDirectories(FAILED_FILE.getParent());
            MAPPER.writeValue(FAILED_FILE.toFile(), new LinkedHashMap<>(failedFiles));
        } catch (IOException e) {
            System.err.println("[RAG] 保存失败清单失败: " + e.getMessage());
        }
    }

    // ================================
    //  去重工具
    // ================================

    /** 分块内容去重 */
    private List<DocumentChunk> dedupChunks(List<DocumentChunk> chunks) {
        Set<String> seen = new HashSet<>();
        List<DocumentChunk> result = new ArrayList<>();
        for (DocumentChunk c : chunks) {
            String hash = sha256(c.getContent());
            if (seen.add(hash)) {
                result.add(c);
            }
        }
        return result;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    // ================================
    //  管理接口
    // ================================

    public Map<String, Object> getProgress() {
        return Map.of("current", progressCurrent, "total", progressTotal,
                "stage", progressStage,
                "percent", progressTotal > 0
                        ? (int) ((double) progressCurrent / progressTotal * 100) : 0);
    }

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

    public Path getKbDir() {
        try { Files.createDirectories(KB_DIR); } catch (IOException ignored) {}
        return KB_DIR;
    }

    public Set<String> getProcessedFiles() {
        return Collections.unmodifiableSet(processedFiles);
    }

    public Map<String, String> getFailedFiles() {
        return Collections.unmodifiableMap(failedFiles);
    }

    /** 清除失败文件中的某个条目（允许重试） */
    public void clearFailedFile(String fileName) {
        failedFiles.remove(fileName);
        saveFailedFiles();
    }

    public void removeDocument(String fileName) {
        try {
            vectorStore.removeBySource(fileName);
        } catch (IOException e) {
            System.err.println("[RAG] 删除索引失败: " + e.getMessage());
        }
        // 从断点中也移除
        processedFiles.removeIf(f -> f.endsWith(fileName));
        saveCheckpoint();
        try { vectorStore.persist(); } catch (IOException e) {
            System.err.println("[RAG] 持久化失败: " + e.getMessage());
        }
    }

    public void clearAll() {
        try {
            vectorStore.clear();
        } catch (IOException e) {
            System.err.println("[RAG] 清空索引失败: " + e.getMessage());
        }
        processedFiles.clear();
        failedFiles.clear();
        saveCheckpoint();
        saveFailedFiles();
        try { vectorStore.persist(); } catch (IOException e) {
            System.err.println("[RAG] 持久化失败: " + e.getMessage());
        }
    }

    /** 清除断点缓存（强制全量重建） */
    public void resetCheckpoint() {
        processedFiles.clear();
        saveCheckpoint();
    }
}
