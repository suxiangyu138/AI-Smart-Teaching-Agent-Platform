package com.chatbot;

import com.chatbot.rag.crawler.CrawlRequest;
import com.chatbot.rag.crawler.WebCrawlerService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 网页爬虫 REST API — 用爬虫构建数学知识库
 * <p>
 * 端点：
 * - POST   /api/crawler/start    启动爬虫任务
 * - POST   /api/crawler/cancel   取消当前任务
 * - GET    /api/crawler/progress 查看进度
 * - GET    /api/crawler/stats    总览统计
 * - POST   /api/crawler/quick    快捷爬取（简化参数）
 *
 * @author suxiangyu
 */
@RestController
@RequestMapping("/api/crawler")
public class CrawlerController {

    private final WebCrawlerService crawlerService;

    public CrawlerController(WebCrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    /**
     * 启动爬虫任务
     * <p>
     * 请求体示例：
     * <pre>
     * {
     *   "seedUrls": ["https://example.com/math/algebra/"],
     *   "maxDepth": 2,
     *   "maxPages": 50,
     *   "stage": "junior",
     *   "pathKeywords": ["math", "代数", "几何"],
     *   "politenessDelayMs": 1500
     * }
     * </pre>
     */
    @PostMapping("/start")
    public Map<String, Object> startCrawl(@RequestBody CrawlRequest req) {
        if (req.getSeedUrls() == null || req.getSeedUrls().isEmpty()) {
            return error("缺少 seedUrls 参数");
        }
        if (req.getMaxPages() <= 0) {
            req.setMaxPages(100);
        }
        if (req.getMaxPages() > 500) {
            return error("maxPages 不能超过 500（保护目标服务器）");
        }
        return crawlerService.startCrawl(req);
    }

    /**
     * 取消当前爬虫任务
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancelCrawl() {
        return crawlerService.cancelCrawl();
    }

    /**
     * 获取爬虫任务进度
     */
    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        return crawlerService.getProgress();
    }

    /**
     * 获取爬虫总体统计
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return crawlerService.getCrawlStats();
    }

    /**
     * 快捷爬取 — 单个 URL + 自动检测学段
     * <p>
     * 请求体示例：
     * <pre>
     * {
     *   "url": "https://example.com/math/algebra/",
     *   "maxPages": 30,
     *   "stage": "auto"
     * }
     * </pre>
     */
    @PostMapping("/quick")
    public Map<String, Object> quickCrawl(@RequestBody Map<String, Object> body) {
        String url = (String) body.get("url");
        if (url == null || url.isBlank()) {
            return error("缺少 url 参数");
        }
        // 校验 URL 格式
        if (!url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            return error("URL 必须以 http:// 或 https:// 开头");
        }

        CrawlRequest req = new CrawlRequest();
        req.setSeedUrls(List.of(url));
        req.setMaxPages(getInt(body, "maxPages", 50));
        req.setMaxDepth(getInt(body, "maxDepth", 2));

        String stage = (String) body.get("stage");
        if (stage != null && !"auto".equalsIgnoreCase(stage)) {
            req.setStage(stage);
        }

        @SuppressWarnings("unchecked")
        List<String> keywords = (List<String>) body.get("pathKeywords");
        if (keywords != null && !keywords.isEmpty()) {
            req.setPathKeywords(new LinkedHashSet<>(keywords));
        }

        req.setPolitenessDelayMs(getInt(body, "politenessDelayMs", 1000));
        req.setTimeoutSeconds(getInt(body, "timeoutSeconds", 15));

        if (body.containsKey("apiKey")) {
            req.setApiKey((String) body.get("apiKey"));
        }
        if (body.containsKey("baseUrl")) {
            req.setBaseUrl((String) body.get("baseUrl"));
        }

        return crawlerService.startCrawl(req);
    }

    /**
     * Playwright 直接渲染 + 索引（跳过 Jsoup，专治 JS 动态网站）
     */
    @PostMapping("/playwright-index")
    public Map<String, Object> playwrightIndex(@RequestBody Map<String, Object> body) {
        String url = (String) body.get("url");
        if (url == null || url.isBlank()) return error("缺少 url");

        String stage = (String) body.get("stage");
        String apiKey = (String) body.get("apiKey");
        String baseUrl = (String) body.get("baseUrl");

        try {
            int count = crawlerService.renderAndIndex(url, stage, apiKey, baseUrl);
            return Map.of("success", true, "url", url, "chunks", count,
                    "message", "Playwright 渲染 + 索引完成，" + count + " 切片");
        } catch (Exception e) {
            return error("Playwright 索引失败: " + e.getMessage());
        }
    }

    // ================================================================
    //  内置预设（常见数学教育网站模板）
    // ================================================================

    /**
     * 获取推荐的数学爬虫预设配置
     * <p>
     * 内置了一些数学教育网站的爬取策略，包含合适的
     * pathKeywords 和学段推荐，可直接用于 /start 接口。
     */
    @GetMapping("/presets")
    public List<Map<String, Object>> getPresets() {
        return List.of(
                Map.of("name", "初中数学（通用）",
                        "description", "适合大多数数学学习网站的初中内容",
                        "stage", "junior",
                        "pathKeywords", List.of("math", "数学", "代数", "几何", "函数", "方程",
                                "三角形", "圆", "概率", "统计", "初中")),
                Map.of("name", "高中数学（通用）",
                        "description", "适合大多数数学学习网站的高中内容",
                        "stage", "senior",
                        "pathKeywords", List.of("math", "数学", "函数", "导数", "圆锥曲线",
                                "数列", "向量", "立体几何", "概率", "高中", "高考")),
                Map.of("name", "大学数学",
                        "description", "大学数学拓展内容",
                        "stage", "university",
                        "pathKeywords", List.of("微积分", "线性代数", "概率论", "数理统计",
                                "离散数学", "数学分析", "高等数学", "微分方程")),
                Map.of("name", "小学奥数",
                        "description", "小学及奥数入门内容",
                        "stage", "primary",
                        "pathKeywords", List.of("数学", "奥数", "小学", "口算", "应用题",
                                "几何图形", "分数", "小数")),
                Map.of("name", "K12 全面爬取",
                        "description", "自动检测学段，覆盖面最广（需要较大 maxPages）",
                        "stage", "auto",
                        "pathKeywords", List.of("math", "数学", "代数", "几何", "函数",
                                "微积分", "奥数", "高考", "中考", "小学"))
        );
    }

    // ================================================================
    //  工具方法
    // ================================================================

    private int getInt(Map<String, Object> body, String key, int defaultVal) {
        Object v = body.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return defaultVal;
    }

    private Map<String, Object> error(String msg) {
        return Map.of("success", false, "error", msg);
    }
}
