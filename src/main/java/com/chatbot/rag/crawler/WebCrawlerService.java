package com.chatbot.rag.crawler;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.document.MathChunkingStrategy;
import com.chatbot.rag.embedding.EmbeddingService;
import com.chatbot.rag.model.DocumentChunk;
import com.chatbot.rag.vector.LuceneVectorStore;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 数学知识库网页爬虫引擎
 * <p>
 * 从数学教育网站爬取结构化文本（保留 LaTeX 公式），
 * 自动分块、向量化并存入知识库向量存储。
 * <p>
 * 核心特性：
 * - 深度受限 BFS 爬取
 * - LaTeX 公式（$$...$$ / $...$）保留
 * - 自动学段检测 + 关键字 URL 过滤
 * - 礼貌爬取（请求间隔 + 同域限制）
 * - 内容去重（SHA-256）
 * - 异步执行 + 进度追踪
 *
 * @author suxiangyu
 */
@Service
public class WebCrawlerService {

    private static final int DEFAULT_DEPTH = 2;
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_DELAY_MS = 1000;
    private static final int DEFAULT_MIN_CHARS = 200;
    private static final int DEFAULT_TIMEOUT = 15;
    private static final int MAX_CONTENT_LENGTH = 50000;

    private final MathChunkingStrategy chunkingStrategy;
    private final EmbeddingService embeddingService;
    private final LuceneVectorStore vectorStore;
    private final PlaywrightClient playwrightClient;

    /** 内容哈希去重 */
    private final Set<String> crawledHashes = ConcurrentHashMap.newKeySet();

    /** 当前任务句柄 */
    private volatile Future<?> currentTask;
    /** 取消标记 */
    private volatile boolean cancelled;

    /** 进度追踪 */
    private volatile int crawledCount;
    private volatile int totalPagesTarget;
    private volatile String currentUrl = "";
    private volatile String status = "idle";
    private volatile long startTimeMs;
    /** 最近索引详情 */
    private final ConcurrentLinkedDeque<Map<String, Object>> recentLogs = new ConcurrentLinkedDeque<>();

    public WebCrawlerService(MathChunkingStrategy chunkingStrategy,
                             EmbeddingService embeddingService,
                             LuceneVectorStore vectorStore,
                             PlaywrightClient playwrightClient) {
        this.chunkingStrategy = chunkingStrategy;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
        this.playwrightClient = playwrightClient;
    }

    /**
     * 启动一个爬虫任务（异步执行）
     */
    public synchronized Map<String, Object> startCrawl(CrawlRequest req) {
        if (currentTask != null && !currentTask.isDone()) {
            return Map.of("success", false, "error", "已有爬虫任务正在运行，请等待完成或先取消");
        }

        cancelled = false;
        crawledCount = 0;
        currentUrl = "";
        totalPagesTarget = Math.min(req.getMaxPages() > 0 ? req.getMaxPages() : DEFAULT_MAX_PAGES, 500);
        status = "running";
        startTimeMs = System.currentTimeMillis();
        recentLogs.clear();

        CrawlRequest safeReq = new CrawlRequest();
        safeReq.setSeedUrls(req.getSeedUrls());
        safeReq.setMaxDepth(req.getMaxDepth() > 0 ? req.getMaxDepth() : DEFAULT_DEPTH);
        safeReq.setMaxPages(totalPagesTarget);
        safeReq.setStage(req.getStage());
        safeReq.setAllowedDomains(req.getAllowedDomains());
        safeReq.setPathKeywords(req.getPathKeywords());
        safeReq.setPolitenessDelayMs(req.getPolitenessDelayMs() > 0 ? req.getPolitenessDelayMs() : DEFAULT_DELAY_MS);
        safeReq.setMinCharsPerPage(req.getMinCharsPerPage() > 0 ? req.getMinCharsPerPage() : DEFAULT_MIN_CHARS);
        safeReq.setTimeoutSeconds(req.getTimeoutSeconds() > 0 ? req.getTimeoutSeconds() : DEFAULT_TIMEOUT);
        safeReq.setUserAgent(req.getUserAgent() != null ? req.getUserAgent() : "MathKnowledgeCrawler/2.0");
        safeReq.setApiKey(req.getApiKey());
        safeReq.setBaseUrl(req.getBaseUrl());

        currentTask = CompletableFuture.runAsync(() -> executeCrawl(safeReq));

        return Map.of("success", true, "message",
                "爬虫任务已启动，种子 URL: " + safeReq.getSeedUrls().size() + " 个，"
                        + "最大页数: " + totalPagesTarget);
    }

    /**
     * Playwright 渲染单个 URL 并直接入库（跳过 BFS 爬取队列）
     */
    public int renderAndIndex(String url, String stage, String apiKey, String baseUrl) throws Exception {
        if (!playwrightClient.isAvailable()) {
            throw new IOException("Playwright 渲染服务未启动 (127.0.0.1:8002)");
        }
        String html = playwrightClient.render(url, null, 2);
        Document doc = Jsoup.parse(html, url);
        String text = extractContent(doc, url);
        text = FormulaNormalizer.normalize(text);

        if (text.length() < 100) {
            throw new IOException("提取内容过短: " + text.length() + " 字符");
        }

        CrawlRequest req = new CrawlRequest();
        req.setStage(stage);
        if (apiKey != null) req.setApiKey(apiKey);
        if (baseUrl != null) req.setBaseUrl(baseUrl);

        // 分块计数
        String stage2 = stage != null ? stage : detectStageAutomatically(text);
        String sourceFile = "crawl:" + normalizeSourceName(url);
        List<DocumentChunk> chunks = chunkingStrategy.chunkDocument(text, sourceFile, stage2);
        if (chunks.isEmpty()) {
            throw new IOException("分块结果为空");
        }
        List<String> chunkTexts = chunks.stream().map(DocumentChunk::getContent).collect(Collectors.toList());
        // 统一本地向量（维度与索引一致）
        List<float[]> embeddings = embeddingService.embedBatch(chunkTexts, null, null);
        vectorStore.indexDocuments(chunks, embeddings, sourceFile);
        vectorStore.persist();
        return chunks.size();
    }

    /**
     * 取消当前爬虫任务
     */
    public Map<String, Object> cancelCrawl() {
        cancelled = true;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        status = "cancelled";
        return Map.of("success", true, "message", "爬虫任务已取消",
                "crawledPages", crawledCount);
    }

    /**
     * 获取当前任务进度
     */
    public Map<String, Object> getProgress() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("status", status);
        p.put("crawledPages", crawledCount);
        p.put("totalPagesTarget", totalPagesTarget);
        p.put("currentUrl", currentUrl);
        if (totalPagesTarget > 0) {
            p.put("percent", (int) ((double) crawledCount / totalPagesTarget * 100));
        } else {
            p.put("percent", 0);
        }
        if (startTimeMs > 0 && status.equals("running")) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            p.put("elapsedSeconds", elapsed / 1000);
        }
        p.put("totalHashes", crawledHashes.size());
        // 最近10条日志
        List<Map<String, Object>> logs = new ArrayList<>(recentLogs);
        p.put("recentLogs", logs.size() > 10 ? logs.subList(logs.size() - 10, logs.size()) : logs);
        return p;
    }

    // ================================================================
    //  执行引擎：BFS 爬取
    // ================================================================

    private void executeCrawl(CrawlRequest req) {
        try {
            // 解析域名白名单
            Set<String> domainFilter = buildDomainFilter(req);

            // BFS 队列：{ url, depth }
            Queue<UrlDepth> queue = new ConcurrentLinkedQueue<>();
            Set<String> visited = ConcurrentHashMap.newKeySet();

            for (String seed : req.getSeedUrls()) {
                String normalized = normalizeUrl(seed);
                if (normalized != null && visited.add(normalized)) {
                    queue.add(new UrlDepth(normalized, 0));
                }
            }

            if (queue.isEmpty()) {
                status = "error";
                appendLog("error", "无有效种子 URL");
                return;
            }

            appendLog("info", "BFS 爬取开始，队列初始: " + queue.size() + " 个种子 URL");

            while (!queue.isEmpty() && !cancelled && crawledCount < req.getMaxPages()) {
                UrlDepth ud = queue.poll();
                if (ud == null) break;

                String url = ud.url;
                int depth = ud.depth;
                currentUrl = url;

                // 域名过滤
                if (!passesDomainFilter(url, domainFilter)) {
                    continue;
                }

                try {
                    // 礼貌延迟
                    if (crawledCount > 0) {
                        Thread.sleep(req.getPolitenessDelayMs());
                    }

                    // 抓取页面（完整浏览器模拟 Headers + 重试）
                    Document doc = fetchPage(url, req);

                    // 提取文本内容
                    String extracted = extractContent(doc, url);
                    if (extracted.length() >= req.getMinCharsPerPage()) {
                        // 去重
                        String hash = sha256(extracted);
                        if (crawledHashes.add(hash)) {
                            // 索引到知识库
                            indexPage(extracted, url, req);
                            crawledCount++;
                            appendLog("indexed", url + " (" + extracted.length() + " 字符)");
                        } else {
                            appendLog("skipped", url + " (内容重复)");
                        }
                    } else if (playwrightClient.isAvailable()) {
                        // Jsoup 提取过短 → 尝试 Playwright 渲染
                        appendLog("info", url + " → Jsoup 文本过短(" + extracted.length() + "字符)，回退 Playwright");
                        try {
                            String html = playwrightClient.render(url, null, 1);
                            Document pwDoc = Jsoup.parse(html, url);
                            String pwText = extractContent(pwDoc, url);
                            if (pwText.length() >= req.getMinCharsPerPage()) {
                                String hash = sha256(pwText);
                                if (crawledHashes.add(hash)) {
                                    indexPage(pwText, url, req);
                                    crawledCount++;
                                    appendLog("indexed", url + " [Playwright] (" + pwText.length() + " 字符)");
                                }
                            } else {
                                appendLog("skipped", url + " (Playwright 文本仍过短: " + pwText.length() + " 字符)");
                            }
                        } catch (Exception pwEx) {
                            appendLog("error", url + " → Playwright 回退失败: " + pwEx.getMessage());
                        }
                    } else {
                        appendLog("skipped", url + " (文本过短: " + extracted.length() + " 字符)");
                    }

                    // BFS 扩展（未达深度上限时）
                    if (depth < req.getMaxDepth() && crawledCount < req.getMaxPages()) {
                        Elements links = doc.select("a[href]");
                        for (Element link : links) {
                            if (cancelled || crawledCount >= req.getMaxPages()) break;

                            String href = link.absUrl("href");
                            String normalized = normalizeUrl(href);
                            if (normalized == null) continue;

                            // 路径关键词过滤
                            if (!passesPathFilter(normalized, req.getPathKeywords())) {
                                continue;
                            }

                            if (visited.add(normalized)) {
                                queue.add(new UrlDepth(normalized, depth + 1));
                            }
                        }
                    }

                } catch (Exception e) {
                    appendLog("error", url + " → " + e.getMessage());
                }
            }

            if (cancelled) {
                status = "cancelled";
            } else if (crawledCount >= req.getMaxPages()) {
                status = "completed_max";
            } else {
                status = "completed_done";
            }

            // 持久化
            try {
                vectorStore.persist();
            } catch (IOException e) {
                appendLog("error", "持久化失败: " + e.getMessage());
            }

            appendLog("info", String.format(
                    "爬取结束: %d 页已索引, 状态=%s", crawledCount, status));

        } catch (Exception e) {
            status = "error";
            appendLog("error", "爬虫异常: " + e.getMessage());
        }
    }

    // ================================================================
    //  内容提取
    // ================================================================

    /**
     * 从 HTML 文档中提取正文文本
     * <p>
     * 策略：
     * 1. 移除无用标签（script, style, nav, footer, header, aside）
     * 2. 优先使用语义化容器（article, main, .content, .post-body 等）
     * 3. 保留 LaTeX 公式（$$...$$ / $...$）
     * 4. 保留标题标签（h1-h6）作为结构标记
     * 5. 保留列表（li）、段落（p）、表格（table）结构
     */
    /** 抓取页面（Jsoup → Playwright 回退） */
    private Document fetchPage(String url, CrawlRequest req) throws IOException {
        // 先试 Jsoup（快速，适合服务端渲染页面）
        IOException lastEx = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent(req.getUserAgent())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Accept-Encoding", "gzip, deflate, br")
                        .header("Cache-Control", "max-age=0")
                        .header("sec-ch-ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\"")
                        .header("sec-ch-ua-mobile", "?0")
                        .header("sec-ch-ua-platform", "\"Windows\"")
                        .header("sec-fetch-dest", "document")
                        .header("sec-fetch-mode", "navigate")
                        .header("sec-fetch-site", "same-origin")
                        .header("sec-fetch-user", "?1")
                        .header("Upgrade-Insecure-Requests", "1")
                        .referrer("https://www.google.com/")
                        .timeout(req.getTimeoutSeconds() * 1000)
                        .followRedirects(true)
                        .maxBodySize(5 * 1024 * 1024)
                        .get();
                return doc;
            } catch (IOException e) {
                lastEx = e;
                try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
            }
        }
        // Jsoup 失败 → Playwright 回退
        if (playwrightClient.isAvailable()) {
            try {
                String html = playwrightClient.render(url, null, 1);
                return Jsoup.parse(html, url);
            } catch (Exception e) {
                appendLog("error", url + " → Playwright 渲染也失败: " + e.getMessage());
            }
        }
        throw lastEx != null ? lastEx : new IOException("抓取失败且无 Playwright: " + url);
    }

    private String extractContent(Document doc, String url) {
        // 移除干扰元素
        doc.select("script, style, nav, footer, header, aside, "
                + ".nav, .navbar, .sidebar, .footer, .header, .menu, "
                + ".advertisement, .ad, .comment, .comments, "
                + "noscript, iframe, [role=navigation]").remove();

        // 尝试定位正文容器
        Element body = doc.body();
        if (body == null) {
            return "";
        }

        // 优先取主内容区
        Element main = body.selectFirst("article, main, "
                + "[role=main], .content, .post-content, .post-body, "
                + ".entry-content, .article-content, .markdown-body, "
                + ".doc-content, #content, #main, #article");

        if (main != null) {
            body = main;
        }

        // 提取为结构化文本（保留换行结构）
        StringBuilder sb = new StringBuilder();

        // 标题
        String title = doc.title();
        if (title != null && !title.isBlank()) {
            sb.append("# ").append(title.trim()).append("\n\n");
        }

        // 处理 body 的子元素，保留结构化信息
        extractElementText(body, sb);

        String text = sb.toString();

        // 清理：压缩多余空行
        text = text.replaceAll("\n{4,}", "\n\n\n");
        // 压缩多余空格但不破坏 LaTeX
        text = text.replaceAll("[ \t]{2,}", " ");

        // 标准化数学公式
        text = FormulaNormalizer.normalize(text);

        // 截断过长内容
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n... [内容过长已截断]";
        }

        return text.trim();
    }

    /**
     * 递归提取元素文本，保留结构
     */
    private void extractElementText(Element el, StringBuilder sb) {
        for (Element child : el.children()) {
            String tag = child.tagName().toLowerCase();

            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    int level = tag.charAt(1) - '0';
                    sb.append("\n").append("#".repeat(level)).append(" ")
                            .append(child.wholeText().trim()).append("\n\n");
                }
                case "p" -> {
                    sb.append(child.wholeText().trim()).append("\n\n");
                }
                case "li" -> {
                    sb.append("- ").append(child.wholeText().trim()).append("\n");
                }
                case "br" -> {
                    sb.append("\n");
                }
                case "pre" -> {
                    sb.append("```\n").append(child.wholeText()).append("\n```\n\n");
                }
                case "code" -> {
                    // 行内代码
                    if (!"pre".equals(child.parent().tagName().toLowerCase())) {
                        sb.append("`").append(child.wholeText()).append("`");
                    }
                }
                case "table" -> {
                    extractTable(child, sb);
                }
                case "img" -> {
                    String alt = child.attr("alt");
                    if (alt != null && !alt.isBlank()) {
                        sb.append("[图: ").append(alt.trim()).append("]\n\n");
                    }
                }
                case "blockquote" -> {
                    String[] lines = child.wholeText().trim().split("\n");
                    for (String line : lines) {
                        sb.append("> ").append(line.trim()).append("\n");
                    }
                    sb.append("\n");
                }
                case "hr" -> {
                    sb.append("---\n\n");
                }
                case "math", "annotation" -> {
                    // MathML / LaTeX annotation
                    sb.append(child.wholeText()).append("\n");
                }
                default -> {
                    // 递归处理未识别标签
                    if (child.childrenSize() > 0) {
                        extractElementText(child, sb);
                    } else {
                        String text = child.wholeText().trim();
                        if (!text.isEmpty()) {
                            sb.append(text).append("\n\n");
                        }
                    }
                }
            }
        }
    }

    /**
     * 提取表格为 Markdown 格式
     */
    private void extractTable(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        sb.append("\n");
        boolean headerDone = false;
        for (Element row : rows) {
            Elements cells = row.select("th, td");
            if (cells.isEmpty()) continue;

            sb.append("|");
            for (Element cell : cells) {
                sb.append(" ").append(cell.wholeText().trim()).append(" |");
            }
            sb.append("\n");

            // 表头分隔行
            if (!headerDone) {
                headerDone = true;
                sb.append("|");
                for (int i = 0; i < cells.size(); i++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    // ================================================================
    //  索引管道：文本 → 分块 → 嵌入 → 向量存储
    // ================================================================

    private void indexPage(String text, String sourceUrl, CrawlRequest req) {
        // 检测学段
        String stage = req.getStage();
        if (stage == null || stage.isBlank()) {
            stage = detectStageAutomatically(text);
        }

        // 生成来源文件名（基于 URL）
        String sourceFile = "crawl:" + normalizeSourceName(sourceUrl);

        // 分块（复用现有数学分块策略）
        List<DocumentChunk> chunks = chunkingStrategy.chunkDocument(text, sourceFile, stage);
        if (chunks.isEmpty()) {
            return;
        }

        // 向量嵌入：统一本地向量（维度与索引一致）
        List<String> chunkTexts = chunks.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());
        List<float[]> embeddings = embeddingService.embedBatch(chunkTexts, null, null);

        // 入库
        try {
            vectorStore.indexDocuments(chunks, embeddings, sourceFile);
        } catch (IOException e) {
            System.err.println("[RAG] 爬虫内容入库失败: " + e.getMessage());
        }
    }

    // ================================================================
    //  学段自动检测
    // ================================================================

    /** 大学 */
    private static final Set<String> UNI_KW = Set.of(
            "微积分", "线性代数", "矩阵", "特征值", "离散数学", "数理统计",
            "正态分布", "ε-δ", "级数", "多元函数", "偏导数", "重积分",
            "微分方程", "群论", "概率论", "数学分析", "高等数学", "泛函分析",
            "抽象代数", "数值分析", "复变函数", "常微分方程", "随机过程",
            "多元统计", "实变函数", "拓扑学", "图论", "组合数学"
    );

    /** 高中 */
    private static final Set<String> SENIOR_KW = Set.of(
            "导数", "微分", "积分", "圆锥曲线", "数列", "向量", "复数",
            "立体几何", "排列组合", "二项式定理", "三角函数", "参数方程",
            "极坐标", "空间向量", "双曲线", "抛物线", "椭圆", "对数",
            "指数函数", "幂函数", "等差数列", "等比数列", "正弦定理",
            "余弦定理", "基本不等式", "柯西不等式", "二次函数", "绝对值不等式",
            "线性规划", "三视图", "二面角", "异面直线", "线面角",
            "高考", "高中数学", "必修", "选择性必修", "选修"
    );

    /** 初中 */
    private static final Set<String> JUNIOR_KW = Set.of(
            "一次函数", "二次函数", "三角形", "全等", "勾股定理", "圆",
            "方程", "不等式", "概率", "统计", "有理数", "实数", "无理数",
            "平面直角坐标系", "平行四边形", "矩形", "菱形", "正方形",
            "梯形", "一元一次方程", "二元一次方程组", "一元二次方程",
            "分式方程", "因式分解", "整式", "幂的运算", "相似三角形",
            "锐角三角函数", "投影与视图", "中考", "初中数学"
    );

    /** 小学 */
    private static final Set<String> PRIMARY_KW = Set.of(
            "口算", "四则运算", "乘法口诀", "分数", "小数", "百分数",
            "面积", "体积", "周长", "应用题", "图形", "年月日",
            "克与千克", "米与厘米", "人民币", "钟表", "线段", "角",
            "长方形", "正方形", "三角形面积", "梯形面积", "因数倍数",
            "质数合数", "最大公约数", "最小公倍数", "小学数学"
    );

    private String detectStageAutomatically(String text) {
        int primaryScore = countKeywords(text, PRIMARY_KW);
        int juniorScore = countKeywords(text, JUNIOR_KW);
        int seniorScore = countKeywords(text, SENIOR_KW);
        int uniScore = countKeywords(text, UNI_KW);

        if (uniScore > seniorScore && uniScore > juniorScore && uniScore > primaryScore) {
            return UnifiedChatRequest.STAGE_UNIVERSITY;
        }
        if (seniorScore > juniorScore && seniorScore > primaryScore) {
            return UnifiedChatRequest.STAGE_SENIOR;
        }
        if (juniorScore > primaryScore) {
            return UnifiedChatRequest.STAGE_JUNIOR;
        }
        if (primaryScore > 0) {
            return UnifiedChatRequest.STAGE_PRIMARY;
        }
        return UnifiedChatRequest.STAGE_JUNIOR; // 默认初中
    }

    private int countKeywords(String text, Set<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                count++;
            }
        }
        return count;
    }

    // ================================================================
    //  URL 处理工具
    // ================================================================

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            // 只处理 http/https
            String lower = url.toLowerCase().trim();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return null;
            }
            // 移除 Fragment
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            String query = uri.getQuery();
            String normalized = uri.getScheme() + "://"
                    + uri.getHost().toLowerCase()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + path
                    + (query != null ? "?" + query : "");
            // 移除尾部 /
            if (normalized.endsWith("/") && !path.equals("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean passesDomainFilter(String url, Set<String> allowedDomains) {
        if (allowedDomains == null || allowedDomains.isEmpty()) {
            return true;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            for (String domain : allowedDomains) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean passesPathFilter(String url, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }
        String lower = url.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private Set<String> buildDomainFilter(CrawlRequest req) {
        Set<String> domains = new LinkedHashSet<>();
        if (req.getAllowedDomains() != null && !req.getAllowedDomains().isEmpty()) {
            domains.addAll(req.getAllowedDomains());
        } else {
            // 默认：限制为种子 URL 同域
            for (String seed : req.getSeedUrls()) {
                try {
                    String host = URI.create(seed).getHost();
                    if (host != null) {
                        domains.add(host.toLowerCase());
                    }
                } catch (Exception ignored) {}
            }
        }
        return domains;
    }

    private String normalizeSourceName(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) {
                return uri.getHost() + "_index";
            }
            // 取路径最后一段作为文件名
            String[] segments = path.split("/");
            String last = segments[segments.length - 1];
            if (last.isEmpty() && segments.length > 1) {
                last = segments[segments.length - 2];
            }
            // 移除扩展名
            int dot = last.lastIndexOf('.');
            if (dot > 0) {
                last = last.substring(0, dot);
            }
            String name = uri.getHost() + "_" + last;
            // 截断过长的名字
            if (name.length() > 80) {
                name = name.substring(0, 80);
            }
            return name.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]", "_");
        } catch (Exception e) {
            return "crawled_page";
        }
    }

    // ================================================================
    //  工具方法
    // ================================================================

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

    private void appendLog(String type, String message) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("type", type);
        log.put("message", message);
        log.put("time", System.currentTimeMillis());
        recentLogs.add(log);
        // 保留最近 500 条
        while (recentLogs.size() > 500) {
            recentLogs.pollFirst();
        }
    }

    // ================================================================
    //  统计
    // ================================================================

    public Map<String, Object> getCrawlStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCrawledPages", crawledCount);
        stats.put("status", status);
        stats.put("uniqueContentHashes", crawledHashes.size());
        return stats;
    }

    // ================================================================
    //  内部类型
    // ================================================================

    private record UrlDepth(String url, int depth) {}
}
