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
            System.getProperty("user.home"), ".deepseek-chatbot", "vector_store");
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

            store.put(doc.getId(), doc);
        }
        indexedFiles.add(sourceFile);
    }

    /**
     * 向量检索 — 余弦相似度 Top-K（分层过滤）
     *
     * @param stage        当前学段
     * @param allowExtend  是否允许大学拓展
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK,
                                      String stage, boolean allowExtend) {
        List<SearchResult> results = new ArrayList<>();
        for (VectorDocument doc : store.values()) {
            if (!passesStageFilter(doc, stage, allowExtend)) {
                continue;
            }
            double similarity = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            double weighted = applyStageWeight(doc, similarity);
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
     * 关键词搜索（降级模式）
     */
    public List<SearchResult> keywordSearch(String query, int topK,
                                             String stage, boolean allowExtend) {
        List<SearchResult> results = new ArrayList<>();
        String qLower = query.toLowerCase();
        String[] keywords = qLower.split("[\\s，。；：！？、]+");

        for (VectorDocument doc : store.values()) {
            if (!passesStageFilter(doc, stage, allowExtend)) {
                continue;
            }
            String content = doc.getContent().toLowerCase();
            int score = 0;
            for (String kw : keywords) {
                if (kw.length() < MIN_KEYWORD_LEN) {
                    continue;
                }
                int idx = content.indexOf(kw);
                while (idx >= 0) {
                    score++;
                    idx = content.indexOf(kw, idx + kw.length());
                }
            }
            if (content.contains(qLower)) {
                score += FULL_MATCH_BONUS;
            }
            if (score > 0) {
                results.add(buildResult(doc, (double) score));
            }
        }
        results.sort(Comparator.comparingDouble(SearchResult::getScore).reversed());
        return results.stream().limit(topK).collect(Collectors.toList());
    }

    /** @deprecated 兼容旧调用 */
    @Deprecated
    public List<SearchResult> keywordSearch(String query, int topK, String gradeFilter) {
        return keywordSearch(query, topK, gradeFilter, false);
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

    /** 学段权重：课内权重 1.0，大学拓展权重 0.6 */
    private double applyStageWeight(VectorDocument doc, double rawSimilarity) {
        String docStage = doc.getGrade();
        if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(docStage)) {
            return rawSimilarity * 0.6;
        }
        return rawSimilarity;
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
}
