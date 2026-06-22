package com.chatbot.rag.vector;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.model.DocumentChunk;
import com.chatbot.rag.model.SearchResult;
import com.chatbot.rag.model.VectorDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量存储引擎
 * <p>
 * 企业特性：
 * - 余弦相似度检索
 * - 按学段/知识点/题型元数据过滤
 * - JSON 文件持久化
 * - 线程安全
 *
 * @author suxiangyu
 */
@Component
public class InMemoryVectorStore {

    private static final Path STORE_DIR = Paths.get(
            System.getProperty("user.dir"), "data", "vector_store");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 关键词搜索短词阈值 */
    private static final int MIN_KEYWORD_LEN = 2;
    /** 完整匹配加分 */
    private static final int FULL_MATCH_BONUS = 5;
    /** 摘要最大长度 */
    private static final int SNIPPET_MAX_LEN = 150;
    /** 余弦相似度接近零判断阈值 */
    private static final double EPSILON = 1e-9;

    /** 向量文档存储 */
    private final Map<String, VectorDocument> store = new ConcurrentHashMap<>();
    /** 来源文件列表 */
    private final Set<String> indexedFiles = ConcurrentHashMap.newKeySet();

    /**
     * 批量入库
     */
    public void indexDocuments(List<DocumentChunk> chunks, List<float[]> embeddings,
                                String sourceFile) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("切块数与向量数不匹配");
        }
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            float[] embedding = embeddings.get(i);

            VectorDocument doc = new VectorDocument();
            doc.setId(chunk.getChunkId());
            doc.setSourceFile(sourceFile);
            doc.setContent(chunk.getContent());
            doc.setEmbedding(embedding);
            doc.setGrade(chunk.getStage());
            doc.setChapterTitle(chunk.getChapterTitle());
            doc.setKnowledgePoint(chunk.getKnowledgePoint());
            doc.setQuestionType(chunk.getQuestionType());
            doc.setWeight(chunk.getWeight());
            doc.setSourceType(chunk.getSourceType());
            doc.setSourceName(chunk.getSourceName());
            doc.setPageNum(chunk.getPageNum());
            doc.setHasFormula(chunk.isHasFormula());
            doc.setFormulaCount(chunk.getFormulaCount());

            store.put(doc.getId(), doc);
        }
        indexedFiles.add(sourceFile);
    }

    /**
     * 向量检索 — 余弦相似度 Top-K（分层过滤）
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK,
                                      String stage, boolean allowExtend) {
        List<SearchResult> results = new ArrayList<>();
        for (VectorDocument doc : store.values()) {
            if (!passesStageFilter(doc, stage, allowExtend)) {
                continue;
            }
            double similarity = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            double weighted = applyAllWeights(doc, similarity);
            results.add(buildResult(doc, weighted));
        }
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());
        return results.stream().limit(topK).collect(Collectors.toList());
    }

    /** @deprecated 兼容旧调用 */
    @Deprecated
    public List<SearchResult> search(float[] q, int topK, String gradeFilter) {
        return search(q, topK, gradeFilter, false);
    }

    /**
     * 多路召回：向量 + 关键词 + 知识点标签三路融合检索
     * <p>
     * 最终得分 = 向量相似度 * 文档权重 + 关键词匹配加分 + 标签匹配加分
     */
    public List<SearchResult> searchMultiRecall(float[] queryEmbedding, String query,
                                                  int topK, String stage, boolean allowExtend) {
        String qLower = query != null ? query.toLowerCase() : "";
        String[] keywords = qLower.split("[\\s，。；：！？、]+");
        Map<String, SearchResult> merged = new LinkedHashMap<>();

        for (VectorDocument doc : store.values()) {
            if (!passesStageFilter(doc, stage, allowExtend)) {
                continue;
            }

            double vectorScore = 0.0;
            if (queryEmbedding != null && doc.getEmbedding() != null) {
                vectorScore = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            }

            double keywordScore = keywordMatchScore(doc.getContent(), keywords, qLower);
            double tagScore = tagMatchScore(doc.getKnowledgePoint(), keywords);
            double docWeight = applyAllWeights(doc, 1.0);

            // 融合公式：向量60% + 关键词25% + 标签15%，再乘文档权重
            double finalScore = (vectorScore * 0.6 + keywordScore * 0.25 + tagScore * 0.15) * docWeight;

            if (finalScore <= 0) {
                continue;
            }

            SearchResult existing = merged.get(doc.getId());
            if (existing == null || finalScore > existing.getScore()) {
                merged.put(doc.getId(), buildResult(doc, finalScore));
            }
        }

        List<SearchResult> results = new ArrayList<>(merged.values());
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());
        return results.stream().limit(topK).collect(Collectors.toList());
    }

    /** 关键词匹配得分（归一化到 [0,1]） */
    private double keywordMatchScore(String content, String[] keywords, String fullQuery) {
        if (content == null) {
            return 0.0;
        }
        String lower = content.toLowerCase();
        int hits = 0;
        for (String kw : keywords) {
            if (kw.length() < MIN_KEYWORD_LEN) {
                continue;
            }
            int idx = lower.indexOf(kw);
            while (idx >= 0) {
                hits++;
                idx = lower.indexOf(kw, idx + kw.length());
            }
        }
        if (lower.contains(fullQuery) && fullQuery.length() > 4) {
            hits += FULL_MATCH_BONUS;
        }
        return Math.min(1.0, hits / 20.0);
    }

    /** 知识点标签匹配得分 */
    private double tagMatchScore(String knowledgePoint, String[] keywords) {
        if (knowledgePoint == null || knowledgePoint.isEmpty()) {
            return 0.0;
        }
        for (String kw : keywords) {
            if (kw.length() >= 2 && knowledgePoint.contains(kw)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /** 综合权重：学段权重 * 文档来源权重 */
    private double applyAllWeights(VectorDocument doc, double rawScore) {
        double stageWeight = 1.0;
        if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(doc.getGrade())) {
            stageWeight = 0.6;
        }
        double docWeight = doc.getWeight() > 0 ? doc.getWeight() : 1.0;
        return rawScore * stageWeight * docWeight;
    }

    /**
     * 关键词搜索（降级模式）
     */
    public List<SearchResult> keywordSearch(String query, int topK,
                                             String stage, boolean allowExtend) {
        return searchMultiRecall(null, query, topK, stage, allowExtend);
    }

    /** @deprecated 兼容旧调用 */
    @Deprecated
    public List<SearchResult> keywordSearch(String query, int topK, String gradeFilter) {
        return keywordSearch(query, topK, gradeFilter, false);
    }

    /** 查询语义增强：追加学段限定 */
    public String enhanceQuery(String query, String stage) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        if (UnifiedChatRequest.STAGE_PRIMARY.equals(stage)) {
            return "小学 " + query;
        }
        if (UnifiedChatRequest.STAGE_JUNIOR.equals(stage)) {
            return "初中 课内 " + query + " 中考考点";
        }
        if (UnifiedChatRequest.STAGE_SENIOR.equals(stage)) {
            return "高中 " + query + " 高考考点";
        }
        return query;
    }

    /** 分层过滤：只取 ≤ 当前学段的文档，大学文档需拓展开关开启 */
    private boolean passesStageFilter(VectorDocument doc, String currentStage,
                                       boolean allowExtend) {
        if (currentStage == null || currentStage.isEmpty()) {
            return true;
        }
        // VectorDocument uses grade field for storage
        String docStage = doc.getGrade();
        if (docStage == null || docStage.isEmpty()) {
            return true;
        }
        if (docStage.equals(currentStage)) {
            return true;
        }
        // 大学文档仅当允许拓展时可见
        if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(docStage)) {
            return allowExtend;
        }
        // 低学段文档对高学段可见（如高中可看初中资料复习）
        return isStageBelowOrEqual(docStage, currentStage);
    }

    private boolean isStageBelowOrEqual(String docStage, String currentStage) {
        Integer docOrder = UnifiedChatRequest.STAGE_ORDER.get(docStage);
        Integer curOrder = UnifiedChatRequest.STAGE_ORDER.get(currentStage);
        if (docOrder == null || curOrder == null) {
            return true;
        }
        return docOrder <= curOrder;
    }

    private SearchResult buildResult(VectorDocument doc, double score) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(doc.getId());
        chunk.setSourceFile(doc.getSourceFile());
        chunk.setContent(doc.getContent());
        chunk.setStage(doc.getGrade());
        chunk.setChapterTitle(doc.getChapterTitle());
        chunk.setKnowledgePoint(doc.getKnowledgePoint());
        chunk.setQuestionType(doc.getQuestionType());
        chunk.setWeight(doc.getWeight());
        chunk.setSourceType(doc.getSourceType());
        chunk.setSourceName(doc.getSourceName());
        chunk.setPageNum(doc.getPageNum());

        SearchResult result = new SearchResult();
        result.setChunk(chunk);
        result.setScore(score);
        result.setSourceFile(doc.getSourceFile());
        result.setSnippet(snippet(doc.getContent(), SNIPPET_MAX_LEN));
        return result;
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            return 0.0;
        }
        int len = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < len; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA < EPSILON || normB < EPSILON) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String snippet(String text, int maxLen) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ===== 持久化 =====

    public void persist() throws IOException {
        Files.createDirectories(STORE_DIR);

        List<Map<String, Object>> docs = new ArrayList<>();
        for (VectorDocument doc : store.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", doc.getId());
            m.put("sourceFile", doc.getSourceFile());
            m.put("content", doc.getContent());
            m.put("embedding", doc.getEmbedding());
            m.put("grade", doc.getGrade());
            m.put("chapterTitle", doc.getChapterTitle());
            m.put("knowledgePoint", doc.getKnowledgePoint());
            m.put("questionType", doc.getQuestionType());
            m.put("hasFormula", doc.isHasFormula());
            m.put("formulaCount", doc.getFormulaCount());
            docs.add(m);
        }
        MAPPER.writeValue(STORE_DIR.resolve("documents.json").toFile(), docs);
        MAPPER.writeValue(STORE_DIR.resolve("indexed_files.json").toFile(),
                new ArrayList<>(indexedFiles));
    }

    @SuppressWarnings("unchecked")
    public void load() throws IOException {
        Path docsFile = STORE_DIR.resolve("documents.json");
        if (!Files.exists(docsFile)) {
            return;
        }

        List<Map<String, Object>> docs = MAPPER.readValue(docsFile.toFile(),
                new TypeReference<List<Map<String, Object>>>() {});

        store.clear();
        for (Map<String, Object> m : docs) {
            VectorDocument doc = new VectorDocument();
            doc.setId((String) m.get("id"));
            doc.setSourceFile((String) m.get("sourceFile"));
            doc.setContent((String) m.get("content"));

            Object emb = m.get("embedding");
            if (emb instanceof List) {
                List<Number> list = (List<Number>) emb;
                float[] arr = new float[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    arr[i] = list.get(i).floatValue();
                }
                doc.setEmbedding(arr);
            }

            doc.setGrade((String) m.get("grade"));
            doc.setChapterTitle((String) m.get("chapterTitle"));
            doc.setKnowledgePoint((String) m.get("knowledgePoint"));
            doc.setQuestionType((String) m.get("questionType"));
            // 公式元数据（向后兼容：旧 JSON 中可能不存在）
            Object hf = m.get("hasFormula");
            if (hf instanceof Boolean) doc.setHasFormula((Boolean) hf);
            Object fc = m.get("formulaCount");
            if (fc instanceof Number) doc.setFormulaCount(((Number) fc).intValue());
            store.put(doc.getId(), doc);
        }

        Path idxFile = STORE_DIR.resolve("indexed_files.json");
        if (Files.exists(idxFile)) {
            List<String> files = MAPPER.readValue(idxFile.toFile(),
                    new TypeReference<List<String>>() {});
            indexedFiles.addAll(files);
        }
    }

    // ===== 管理操作 =====

    public int size() {
        return store.size();
    }

    public int indexedFileCount() {
        return indexedFiles.size();
    }

    public Set<String> getIndexedFiles() {
        return Collections.unmodifiableSet(indexedFiles);
    }

    public Map<String, Integer> countByGrade() {
        Map<String, Integer> counts = new HashMap<>(4);
        for (VectorDocument doc : store.values()) {
            String g = doc.getGrade() != null ? doc.getGrade() : "unknown";
            counts.merge(g, 1, (a, b) -> a + b);
        }
        return counts;
    }

    public void removeBySource(String sourceFile) {
        store.entrySet().removeIf(e -> sourceFile.equals(e.getValue().getSourceFile()));
        indexedFiles.remove(sourceFile);
    }

    public void clear() {
        store.clear();
        indexedFiles.clear();
    }

    public long totalChars() {
        return store.values().stream()
                .mapToLong(d -> d.getContent() != null ? d.getContent().length() : 0)
                .sum();
    }

    public long diskSizeBytes() {
        Path docsFile = STORE_DIR.resolve("documents.json");
        if (Files.exists(docsFile)) {
            try {
                return Files.size(docsFile);
            } catch (IOException e) {
                return 0;
            }
        }
        return 0;
    }

    // ===== 公式标准化与统计 =====

    /**
     * 遍历所有文档，检测裸 LaTeX 命令，包裹为 $$...$$，
     * 更新 hasFormula/formulaCount 元数据，持久化。
     * 返回修改统计信息。
     */
    public Map<String, Object> normalizeFormulas() {
        int totalDocs = store.size();
        int modifiedDocs = 0;
        int formulaDocsFound = 0;
        int formulaCountTotal = 0;

        for (VectorDocument doc : store.values()) {
            String content = doc.getContent();
            if (content == null || content.isEmpty()) continue;

            boolean hasLatex = com.chatbot.rag.crawler.FormulaNormalizer.hasLatexCommands(content);
            int existingCount = com.chatbot.rag.crawler.FormulaNormalizer.countFormulaBlocks(content);

            String normalized = com.chatbot.rag.crawler.FormulaNormalizer.normalizeBatch(content);
            boolean contentChanged = !normalized.equals(content);

            int newCount = com.chatbot.rag.crawler.FormulaNormalizer.countFormulaBlocks(normalized);

            doc.setHasFormula(hasLatex || existingCount > 0);
            doc.setFormulaCount(newCount);

            if (hasLatex) formulaDocsFound++;
            formulaCountTotal += newCount;

            if (contentChanged) {
                doc.setContent(normalized);
                modifiedDocs++;
            }
        }

        if (modifiedDocs > 0) {
            try {
                persist();
            } catch (IOException e) {
                // 持久化失败不影响内存状态
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDocuments", totalDocs);
        result.put("modifiedDocuments", modifiedDocs);
        result.put("documentsWithFormulas", formulaDocsFound);
        result.put("totalFormulaBlocks", formulaCountTotal);
        return result;
    }

    /** 统计含公式的文档数 */
    public long countDocumentsWithFormulas() {
        return store.values().stream().filter(VectorDocument::isHasFormula).count();
    }

    /** 统计所有文档中的公式块总数 */
    public long countTotalFormulaBlocks() {
        return store.values().stream()
                .mapToLong(d -> (long) d.getFormulaCount())
                .sum();
    }
}
