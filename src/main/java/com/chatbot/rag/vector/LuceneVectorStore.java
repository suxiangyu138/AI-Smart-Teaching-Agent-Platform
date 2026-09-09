package com.chatbot.rag.vector;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.crawler.FormulaNormalizer;
import com.chatbot.rag.embedding.EmbeddingService;
import com.chatbot.rag.model.DocumentChunk;
import com.chatbot.rag.model.SearchResult;
import com.chatbot.rag.model.VectorDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Lucene 磁盘混合检索引擎
 * <p>
 * 面向大规模知识库（百万级切片）设计：
 * - 索引落盘（mmap），内存占用与库大小无关
 * - 双路召回：BM25 全文检索（SmartCN 中文分词）+ HNSW 向量余弦检索（本地 n-gram 嵌入）
 * - RRF（Reciprocal Rank Fusion）融合两路排名
 * - 切片级内容哈希去重（updateDocument 按哈希替换，不会产生重复切片）
 * - 学段分层过滤（只检索 ≤ 当前学段的文档，大学文档需拓展开关）
 * <p>
 * 向量统一为本地 256 维 n-gram 嵌入：维度一致、零成本，云端嵌入维度（1536）与本地
 * 不兼容，混用会破坏向量检索，故本引擎一律使用本地向量。
 *
 * @author suxiangyu
 */
@Component
public class LuceneVectorStore {

    private static final Path INDEX_DIR = Paths.get(
            System.getProperty("user.dir"), "data", "lucene_index");
    private static final Path META_FILE = Paths.get(
            System.getProperty("user.dir"), "data", "lucene_meta.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本地向量维度（与 EmbeddingService.embedLocal 一致） */
    private static final int VECTOR_DIM = 256;
    /** BM25 召回候选数 */
    private static final int BM25_CANDIDATES = 100;
    /** 向量召回候选数 */
    private static final int KNN_CANDIDATES = 200;
    /** RRF 融合常数 */
    private static final int RRF_K = 60;
    /** 摘要最大长度 */
    private static final int SNIPPET_MAX_LEN = 150;
    /** 单文件分块扫描上限（相邻分块扩展用） */
    private static final int MAX_DOCS_PER_FILE = 100_000;

    // 字段名
    private static final String F_ID = "id";
    private static final String F_SOURCE = "sourceFile";
    private static final String F_CONTENT = "content";
    private static final String F_HASH = "chunkHash";
    private static final String F_GRADE = "grade";
    private static final String F_CHAPTER = "chapterTitle";
    private static final String F_KP = "knowledgePoint";
    private static final String F_QTYPE = "questionType";
    private static final String F_SRCTYPE = "sourceType";
    private static final String F_SRCNAME = "sourceName";
    private static final String F_PAGE = "pageNum";
    private static final String F_START = "startChar";
    private static final String F_END = "endChar";
    private static final String F_HASFORMULA = "hasFormula";
    private static final String F_FORMULACOUNT = "formulaCount";
    private static final String F_WEIGHT = "weight";
    private static final String F_VECTOR = "embedding";

    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final EmbeddingService embeddingService;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    /** 全库总字符数（增量维护 + meta.json 持久化） */
    private long totalChars = 0;

    public LuceneVectorStore(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        try {
            Files.createDirectories(INDEX_DIR);
            IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
            iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            writer = new IndexWriter(FSDirectory.open(INDEX_DIR), iwc);
            searcherManager = new SearcherManager(writer, new SearcherFactory());
            loadMetaOrRebuild();
            System.out.println("[RAG] Lucene 索引就绪: " + size() + " 切片, "
                    + indexedFileCount() + " 文档");
        } catch (IOException e) {
            throw new IllegalStateException("[RAG] Lucene 索引初始化失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        try { searcherManager.close(); } catch (IOException ignored) {}
        try { writer.close(); } catch (IOException ignored) {}
    }

    /** 兼容旧接口：Lucene 自带持久化，启动时已加载 */
    public void load() {
        // no-op
    }

    /** 提交未落盘的写入（Lucene 常态持久化，此方法为兼容接口） */
    public void persist() throws IOException {
        writer.commit();
        searcherManager.maybeRefreshBlocking();
    }

    // ================================
    //  入库
    // ================================

    /**
     * 批量入库。切片级哈希去重：内容相同的切片按哈希替换，不会重复存储。
     */
    public void indexDocuments(List<DocumentChunk> chunks, List<float[]> embeddings,
                                String sourceFile) throws IOException {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("切块数与向量数不匹配");
        }

        // 先统计替换带来的字符增量（同一批内重复哈希只算一次）
        long delta = 0;
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Set<String> batchSeen = new HashSet<>();
            for (DocumentChunk c : chunks) {
                String hash = hashContent(c.getContent());
                if (!batchSeen.add(hash)) {
                    continue;
                }
                delta += c.getContent().length() - contentLengthOf(searcher, hash);
            }
        } finally {
            searcherManager.release(searcher);
        }

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            float[] embedding = embeddings.get(i);
            // 兜底：维度不一致时用本地嵌入重算，保证向量字段维度统一
            if (embedding == null || embedding.length != VECTOR_DIM) {
                embedding = embeddingService.embedLocal(chunk.getContent());
            }
            writer.updateDocument(new Term(F_HASH, hashContent(chunk.getContent())),
                    toDocument(chunk, embedding));
        }

        writer.commit();
        searcherManager.maybeRefreshBlocking();
        totalChars = Math.max(0, totalChars + delta);
        saveMeta();
    }

    private Document toDocument(DocumentChunk c, float[] embedding) {
        Document doc = new Document();
        doc.add(new StringField(F_ID, c.getChunkId(), Field.Store.YES));
        doc.add(new StringField(F_SOURCE, c.getSourceFile() != null ? c.getSourceFile() : "", Field.Store.YES));
        doc.add(new TextField(F_CONTENT, c.getContent(), Field.Store.YES));
        doc.add(new StringField(F_HASH, hashContent(c.getContent()), Field.Store.NO));
        doc.add(new StringField(F_GRADE, c.getStage() != null ? c.getStage() : "", Field.Store.YES));
        if (c.getChapterTitle() != null && !c.getChapterTitle().isEmpty()) {
            doc.add(new TextField(F_CHAPTER, c.getChapterTitle(), Field.Store.YES));
        }
        if (c.getKnowledgePoint() != null) {
            doc.add(new StringField(F_KP, c.getKnowledgePoint(), Field.Store.YES));
        }
        if (c.getQuestionType() != null) {
            doc.add(new StringField(F_QTYPE, c.getQuestionType(), Field.Store.YES));
        }
        if (c.getSourceType() != null) {
            doc.add(new StringField(F_SRCTYPE, c.getSourceType(), Field.Store.YES));
        }
        if (c.getSourceName() != null) {
            doc.add(new StoredField(F_SRCNAME, c.getSourceName()));
        }
        if (c.getPageNum() != null) {
            doc.add(new StoredField(F_PAGE, c.getPageNum()));
        }
        doc.add(new StoredField(F_START, c.getStartChar()));
        doc.add(new StoredField(F_END, c.getEndChar()));
        doc.add(new StoredField(F_HASFORMULA, c.isHasFormula() ? 1 : 0));
        doc.add(new StoredField(F_FORMULACOUNT, c.getFormulaCount()));
        doc.add(new StoredField(F_WEIGHT, c.getWeight()));
        doc.add(new KnnFloatVectorField(F_VECTOR, embedding, VectorSimilarityFunction.COSINE));
        return doc;
    }

    private static String hashContent(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    /** 查询指定内容哈希对应切片的字符数（不存在返回 0） */
    private long contentLengthOf(IndexSearcher searcher, String hash) throws IOException {
        TopDocs hits = searcher.search(new TermQuery(new Term(F_HASH, hash)), 1);
        if (hits.totalHits.value == 0) {
            return 0;
        }
        Document d = searcher.storedFields().document(hits.scoreDocs[0].doc);
        String content = d.get(F_CONTENT);
        return content != null ? content.length() : 0;
    }

    // ================================
    //  检索
    // ================================

    /**
     * 双路召回 + RRF 融合检索
     * <p>
     * 得分归一化到 [0, ~1.4]（两路均排名第一且权重 1.0 时为 1.0，学段/文档权重再乘算）。
     */
    public List<SearchResult> searchMultiRecall(float[] queryEmbedding, String query,
                                                  int topK, String stage, boolean allowExtend) {
        if (topK <= 0) {
            return List.of();
        }
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                Query filter = buildStageFilter(stage, allowExtend);
                Query textQuery = buildTextQuery(query);

                // 第一路：BM25 全文召回（无有效词元时跳过）
                ScoreDoc[] bm25ScoreDocs = new ScoreDoc[0];
                if (textQuery != null) {
                    Query bm25 = filter == null ? textQuery : new BooleanQuery.Builder()
                            .add(textQuery, BooleanClause.Occur.MUST)
                            .add(filter, BooleanClause.Occur.FILTER)
                            .build();
                    bm25ScoreDocs = searcher.search(bm25, BM25_CANDIDATES).scoreDocs;
                }

                // 第二路：HNSW 向量余弦召回（带学段过滤）
                ScoreDoc[] knnScoreDocs = new ScoreDoc[0];
                if (queryEmbedding != null && queryEmbedding.length == VECTOR_DIM) {
                    Query knn = new KnnFloatVectorQuery(F_VECTOR, queryEmbedding, KNN_CANDIDATES);
                    Query knnFiltered = filter == null ? knn : new BooleanQuery.Builder()
                            .add(knn, BooleanClause.Occur.MUST)
                            .add(filter, BooleanClause.Occur.FILTER)
                            .build();
                    knnScoreDocs = searcher.search(knnFiltered, KNN_CANDIDATES).scoreDocs;
                }

                // RRF 融合
                Map<Integer, Double> rrf = new LinkedHashMap<>();
                for (int i = 0; i < bm25ScoreDocs.length; i++) {
                    rrf.merge(bm25ScoreDocs[i].doc, 1.0 / (RRF_K + i + 1), Double::sum);
                }
                for (int i = 0; i < knnScoreDocs.length; i++) {
                    rrf.merge(knnScoreDocs[i].doc, 1.0 / (RRF_K + i + 1), Double::sum);
                }
                if (rrf.isEmpty()) {
                    return List.of();
                }

                List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(rrf.entrySet());
                sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                double maxRrf = 1.0 / (RRF_K + 1)
                        + (knnScoreDocs.length > 0 ? 1.0 / (RRF_K + 1) : 0.0);

                List<SearchResult> results = new ArrayList<>();
                for (Map.Entry<Integer, Double> e : sorted) {
                    if (results.size() >= topK) {
                        break;
                    }
                    Document d = searcher.storedFields().document(e.getKey());
                    double score = e.getValue() / maxRrf * applyAllWeights(d);
                    if (score <= 0) {
                        continue;
                    }
                    results.add(buildResult(d, score));
                }
                return results;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 学段分层过滤：只允许 ≤ 当前学段的文档，大学文档需拓展开关 */
    private Query buildStageFilter(String stage, boolean allowExtend) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        Integer curOrder = UnifiedChatRequest.STAGE_ORDER.get(stage);
        if (curOrder == null) {
            return null;
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (Map.Entry<String, Integer> e : UnifiedChatRequest.STAGE_ORDER.entrySet()) {
            if (e.getValue() <= curOrder
                    || (allowExtend && UnifiedChatRequest.STAGE_UNIVERSITY.equals(e.getKey()))) {
                b.add(new TermQuery(new Term(F_GRADE, e.getKey())), BooleanClause.Occur.SHOULD);
            }
        }
        return b.build();
    }

    /** 用与索引一致的分析器切分查询，构建 BM25 查询；无有效词元返回 null */
    private Query buildTextQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> terms = analyze(query);
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String t : terms) {
            // 正文命中权重 1.0，章节标题命中权重 2.0
            b.add(new BoostQuery(new TermQuery(new Term(F_CONTENT, t)), 1.0f),
                    BooleanClause.Occur.SHOULD);
            b.add(new BoostQuery(new TermQuery(new Term(F_CHAPTER, t)), 2.0f),
                    BooleanClause.Occur.SHOULD);
        }
        return b.build();
    }

    private List<String> analyze(String text) {
        List<String> terms = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(F_CONTENT, text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(term.toString());
            }
            ts.end();
        } catch (IOException ignored) {}
        return terms;
    }

    /** 查询语义增强：追加学段限定（与旧引擎保持一致） */
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

    private double applyAllWeights(Document d) {
        double w = 1.0;
        if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(d.get(F_GRADE))) {
            w = 0.6;
        }
        Number wn = d.getField(F_WEIGHT) != null ? d.getField(F_WEIGHT).numericValue() : null;
        if (wn != null && wn.doubleValue() > 0) {
            w *= wn.doubleValue();
        }
        return w;
    }

    private SearchResult buildResult(Document d, double score) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(d.get(F_ID));
        chunk.setSourceFile(d.get(F_SOURCE));
        chunk.setContent(d.get(F_CONTENT));
        chunk.setStage(d.get(F_GRADE));
        chunk.setChapterTitle(d.get(F_CHAPTER));
        chunk.setKnowledgePoint(d.get(F_KP));
        chunk.setQuestionType(d.get(F_QTYPE));
        chunk.setSourceType(d.get(F_SRCTYPE));
        chunk.setSourceName(d.get(F_SRCNAME));
        Number page = d.getField(F_PAGE) != null ? d.getField(F_PAGE).numericValue() : null;
        chunk.setPageNum(page != null ? page.intValue() : null);

        SearchResult result = new SearchResult();
        result.setChunk(chunk);
        result.setScore(score);
        result.setSourceFile(d.get(F_SOURCE));
        String content = d.get(F_CONTENT);
        result.setSnippet(content != null && content.length() > SNIPPET_MAX_LEN
                ? content.substring(0, SNIPPET_MAX_LEN) + "..." : content);
        return result;
    }

    // ================================
    //  上下文扩展支持
    // ================================

    /** 按 id 取文档（上下文扩展用） */
    public VectorDocument getById(String id) {
        if (id == null) {
            return null;
        }
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                TopDocs hits = searcher.search(new TermQuery(new Term(F_ID, id)), 1);
                if (hits.totalHits.value == 0) {
                    return null;
                }
                return toVectorDocument(searcher.storedFields().document(hits.scoreDocs[0].doc));
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** 将文档包装为检索结果（上下文扩展用） */
    public SearchResult wrapResult(VectorDocument doc, double score) {
        SearchResult result = new SearchResult();
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(doc.getId());
        chunk.setSourceFile(doc.getSourceFile());
        chunk.setContent(doc.getContent());
        chunk.setStage(doc.getGrade());
        chunk.setChapterTitle(doc.getChapterTitle());
        chunk.setKnowledgePoint(doc.getKnowledgePoint());
        chunk.setQuestionType(doc.getQuestionType());
        chunk.setSourceType(doc.getSourceType());
        chunk.setSourceName(doc.getSourceName());
        chunk.setPageNum(doc.getPageNum());
        result.setChunk(chunk);
        result.setScore(score);
        result.setSourceFile(doc.getSourceFile());
        result.setSnippet(doc.getContent() != null && doc.getContent().length() > SNIPPET_MAX_LEN
                ? doc.getContent().substring(0, SNIPPET_MAX_LEN) + "..." : doc.getContent());
        return result;
    }

    /**
     * 取同一文档中与锚点相邻的分块（按字符区间排序，前后各取 before/after 个）
     */
    public List<VectorDocument> adjacentChunks(VectorDocument anchor, int before, int after) {
        if (anchor == null || anchor.getSourceFile() == null) {
            return List.of();
        }
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                TopDocs hits = searcher.search(
                        new TermQuery(new Term(F_SOURCE, anchor.getSourceFile())),
                        MAX_DOCS_PER_FILE);
                List<VectorDocument> ordered = new ArrayList<>();
                for (ScoreDoc sd : hits.scoreDocs) {
                    ordered.add(toVectorDocument(searcher.storedFields().document(sd.doc)));
                }
                ordered.sort(Comparator.comparingInt(VectorDocument::getStartChar));

                int pos = 0;
                while (pos < ordered.size()
                        && ordered.get(pos).getStartChar() < anchor.getStartChar()) {
                    pos++;
                }
                List<VectorDocument> neighbors = new ArrayList<>();
                for (int i = Math.max(0, pos - before); i < pos; i++) {
                    neighbors.add(ordered.get(i));
                }
                for (int i = pos + 1; i < Math.min(ordered.size(), pos + 1 + after); i++) {
                    neighbors.add(ordered.get(i));
                }
                return neighbors;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    private VectorDocument toVectorDocument(Document d) {
        VectorDocument vd = new VectorDocument();
        vd.setId(d.get(F_ID));
        vd.setSourceFile(d.get(F_SOURCE));
        vd.setContent(d.get(F_CONTENT));
        vd.setGrade(d.get(F_GRADE));
        vd.setChapterTitle(d.get(F_CHAPTER));
        vd.setKnowledgePoint(d.get(F_KP));
        vd.setQuestionType(d.get(F_QTYPE));
        vd.setSourceType(d.get(F_SRCTYPE));
        vd.setSourceName(d.get(F_SRCNAME));
        Number page = d.getField(F_PAGE) != null ? d.getField(F_PAGE).numericValue() : null;
        vd.setPageNum(page != null ? page.intValue() : null);
        Number start = d.getField(F_START) != null ? d.getField(F_START).numericValue() : null;
        vd.setStartChar(start != null ? start.intValue() : 0);
        Number end = d.getField(F_END) != null ? d.getField(F_END).numericValue() : null;
        vd.setEndChar(end != null ? end.intValue() : 0);
        return vd;
    }

    // ================================
    //  统计与管理
    // ================================

    public int size() {
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            return 0;
        }
    }

    public int indexedFileCount() {
        return countUniqueTerms(F_SOURCE);
    }

    public Set<String> getIndexedFiles() {
        return uniqueTerms(F_SOURCE);
    }

    public Map<String, Integer> countByGrade() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                Terms terms = MultiTerms.getTerms(searcher.getIndexReader(), F_GRADE);
                if (terms == null) {
                    return counts;
                }
                TermsEnum te = terms.iterator();
                BytesRef br;
                while ((br = te.next()) != null) {
                    counts.put(br.utf8ToString(), te.docFreq());
                }
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException ignored) {}
        return counts;
    }

    public void removeBySource(String sourceFile) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        long removed = 0;
        try {
            TopDocs hits = searcher.search(new TermQuery(new Term(F_SOURCE, sourceFile)),
                    MAX_DOCS_PER_FILE);
            for (ScoreDoc sd : hits.scoreDocs) {
                String content = searcher.storedFields().document(sd.doc).get(F_CONTENT);
                if (content != null) {
                    removed += content.length();
                }
            }
        } finally {
            searcherManager.release(searcher);
        }
        writer.deleteDocuments(new Term(F_SOURCE, sourceFile));
        writer.commit();
        searcherManager.maybeRefreshBlocking();
        totalChars = Math.max(0, totalChars - removed);
        saveMeta();
    }

    public void clear() throws IOException {
        writer.deleteAll();
        writer.commit();
        searcherManager.maybeRefreshBlocking();
        totalChars = 0;
        saveMeta();
    }

    public long totalChars() {
        return totalChars;
    }

    public long diskSizeBytes() {
        if (!Files.exists(INDEX_DIR)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(INDEX_DIR)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    // ================================
    //  公式标准化与统计
    // ================================

    /**
     * 遍历所有文档，检测裸 LaTeX 命令，包裹为 $$...$$。
     * 修改内容的分块用本地嵌入重算向量后替换，保证向量字段完整。
     */
    public Map<String, Object> normalizeFormulas() {
        int totalDocs = 0;
        int modifiedDocs = 0;
        int formulaDocsFound = 0;
        int formulaCountTotal = 0;
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                ScoreDoc after = null;
                while (true) {
                    TopDocs page = searcher.searchAfter(after, new MatchAllDocsQuery(),
                            1000, Sort.INDEXORDER);
                    if (page.scoreDocs.length == 0) {
                        break;
                    }
                    for (ScoreDoc sd : page.scoreDocs) {
                        totalDocs++;
                        Document d = searcher.storedFields().document(sd.doc);
                        String content = d.get(F_CONTENT);
                        if (content == null || content.isEmpty()) {
                            continue;
                        }
                        boolean hasLatex = FormulaNormalizer.hasLatexCommands(content);
                        int existingCount = FormulaNormalizer.countFormulaBlocks(content);
                        String normalized = FormulaNormalizer.normalizeBatch(content);
                        boolean changed = !normalized.equals(content);
                        int newCount = FormulaNormalizer.countFormulaBlocks(normalized);
                        if (hasLatex || existingCount > 0) {
                            formulaDocsFound++;
                        }
                        formulaCountTotal += newCount;
                        if (changed) {
                            writer.updateDocument(new Term(F_ID, d.get(F_ID)),
                                    rebuildDocument(d, normalized));
                            modifiedDocs++;
                        }
                    }
                    after = page.scoreDocs[page.scoreDocs.length - 1];
                    if (page.scoreDocs.length < 1000) {
                        break;
                    }
                }
            } finally {
                searcherManager.release(searcher);
            }
            if (modifiedDocs > 0) {
                writer.commit();
                searcherManager.maybeRefreshBlocking();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalDocuments", totalDocs);
            result.put("modifiedDocuments", modifiedDocs);
            result.put("documentsWithFormulas", formulaDocsFound);
            result.put("totalFormulaBlocks", formulaCountTotal);
            return result;
        } catch (IOException e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 用新内容重建文档（保留全部元数据，重算本地向量） */
    private Document rebuildDocument(Document d, String newContent) {
        DocumentChunk c = new DocumentChunk();
        c.setChunkId(d.get(F_ID));
        c.setSourceFile(d.get(F_SOURCE));
        c.setContent(newContent);
        c.setStage(d.get(F_GRADE));
        c.setChapterTitle(d.get(F_CHAPTER));
        c.setKnowledgePoint(d.get(F_KP));
        c.setQuestionType(d.get(F_QTYPE));
        c.setSourceType(d.get(F_SRCTYPE));
        c.setSourceName(d.get(F_SRCNAME));
        Number page = d.getField(F_PAGE) != null ? d.getField(F_PAGE).numericValue() : null;
        c.setPageNum(page != null ? page.intValue() : null);
        Number start = d.getField(F_START) != null ? d.getField(F_START).numericValue() : null;
        c.setStartChar(start != null ? start.intValue() : 0);
        Number end = d.getField(F_END) != null ? d.getField(F_END).numericValue() : null;
        c.setEndChar(end != null ? end.intValue() : 0);
        Number wn = d.getField(F_WEIGHT) != null ? d.getField(F_WEIGHT).numericValue() : null;
        c.setWeight(wn != null ? wn.doubleValue() : 1.0);
        c.setHasFormula(FormulaNormalizer.hasLatexCommands(newContent)
                || FormulaNormalizer.countFormulaBlocks(newContent) > 0);
        c.setFormulaCount(FormulaNormalizer.countFormulaBlocks(newContent));
        return toDocument(c, embeddingService.embedLocal(newContent));
    }

    /** 统计含公式的文档数（全量扫描，管理接口低频使用） */
    public long countDocumentsWithFormulas() {
        return scanFormulaStats()[0];
    }

    /** 统计所有文档中的公式块总数 */
    public long countTotalFormulaBlocks() {
        return scanFormulaStats()[1];
    }

    private long[] scanFormulaStats() {
        long docsWithFormulas = 0;
        long totalBlocks = 0;
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                ScoreDoc after = null;
                while (true) {
                    TopDocs page = searcher.searchAfter(after, new MatchAllDocsQuery(),
                            2000, Sort.INDEXORDER);
                    if (page.scoreDocs.length == 0) {
                        break;
                    }
                    for (ScoreDoc sd : page.scoreDocs) {
                        Document d = searcher.storedFields().document(sd.doc);
                        Number hf = d.getField(F_HASFORMULA) != null
                                ? d.getField(F_HASFORMULA).numericValue() : null;
                        Number fc = d.getField(F_FORMULACOUNT) != null
                                ? d.getField(F_FORMULACOUNT).numericValue() : null;
                        if (hf != null && hf.intValue() > 0) {
                            docsWithFormulas++;
                        }
                        if (fc != null) {
                            totalBlocks += fc.intValue();
                        }
                    }
                    after = page.scoreDocs[page.scoreDocs.length - 1];
                    if (page.scoreDocs.length < 2000) {
                        break;
                    }
                }
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException ignored) {}
        return new long[]{docsWithFormulas, totalBlocks};
    }

    // ================================
    //  meta 持久化（totalChars 增量计数）
    // ================================

    private void loadMetaOrRebuild() {
        try {
            if (Files.exists(META_FILE)) {
                Map<?, ?> meta = MAPPER.readValue(META_FILE.toFile(), Map.class);
                Object tc = meta.get("totalChars");
                if (tc instanceof Number) {
                    totalChars = ((Number) tc).longValue();
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("[RAG] 加载 meta 失败: " + e.getMessage());
        }
        // 无 meta 或损坏 → 全量重建字符计数
        rebuildTotalChars();
        saveMeta();
    }

    private void rebuildTotalChars() {
        long total = 0;
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                int maxDoc = searcher.getIndexReader().maxDoc();
                for (int i = 0; i < maxDoc; i++) {
                    try {
                        String content = searcher.storedFields().document(i).get(F_CONTENT);
                        if (content != null) {
                            total += content.length();
                        }
                    } catch (IOException ignored) {
                        // 已删除文档跳过
                    }
                }
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException ignored) {}
        totalChars = total;
    }

    private void saveMeta() {
        try {
            Files.createDirectories(META_FILE.getParent());
            MAPPER.writeValue(META_FILE.toFile(), Map.of("totalChars", totalChars));
        } catch (IOException e) {
            System.err.println("[RAG] 保存 meta 失败: " + e.getMessage());
        }
    }

    private Set<String> uniqueTerms(String field) {
        Set<String> values = new LinkedHashSet<>();
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                Terms terms = MultiTerms.getTerms(searcher.getIndexReader(), field);
                if (terms == null) {
                    return values;
                }
                TermsEnum te = terms.iterator();
                BytesRef br;
                while ((br = te.next()) != null) {
                    values.add(br.utf8ToString());
                }
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException ignored) {}
        return values;
    }

    private int countUniqueTerms(String field) {
        int count = 0;
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                Terms terms = MultiTerms.getTerms(searcher.getIndexReader(), field);
                if (terms == null) {
                    return 0;
                }
                TermsEnum te = terms.iterator();
                while (te.next() != null) {
                    count++;
                }
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException ignored) {}
        return count;
    }
}
