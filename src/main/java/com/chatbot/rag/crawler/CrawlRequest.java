package com.chatbot.rag.crawler;

import java.util.*;

/**
 * 爬虫任务配置请求
 *
 * @author suxiangyu
 */
public class CrawlRequest {

    /** 起始 URL 列表 */
    private List<String> seedUrls;
    /** 爬取深度（0 = 仅种子页，默认 2） */
    private int maxDepth = 2;
    /** 单次任务最大页面数（默认 100） */
    private int maxPages = 100;
    /** 指定学段（为空则自动检测） */
    private String stage;
    /** 爬取域名白名单（为空则限制为种子 URL 同域） */
    private Set<String> allowedDomains;
    /** URL 路径关键词过滤（如 "math"/"代数"/"几何"，为空则不过滤） */
    private Set<String> pathKeywords;
    /** 请求间隔毫秒（礼貌爬取，默认 1000ms） */
    private int politenessDelayMs = 1000;
    /** 每页最小提取字符数（低于此值跳过） */
    private int minCharsPerPage = 200;
    /** 超时秒数 */
    private int timeoutSeconds = 15;
    /** 用户代理 */
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    /** API Key（用于云端 Embedding，可选） */
    private String apiKey;
    /** Base URL（用于云端 Embedding，可选） */
    private String baseUrl;

    public List<String> getSeedUrls() { return seedUrls; }
    public void setSeedUrls(List<String> v) { seedUrls = v; }
    public int getMaxDepth() { return maxDepth; }
    public void setMaxDepth(int v) { maxDepth = v; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int v) { maxPages = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public Set<String> getAllowedDomains() { return allowedDomains; }
    public void setAllowedDomains(Set<String> v) { allowedDomains = v; }
    public Set<String> getPathKeywords() { return pathKeywords; }
    public void setPathKeywords(Set<String> v) { pathKeywords = v; }
    public int getPolitenessDelayMs() { return politenessDelayMs; }
    public void setPolitenessDelayMs(int v) { politenessDelayMs = v; }
    public int getMinCharsPerPage() { return minCharsPerPage; }
    public void setMinCharsPerPage(int v) { minCharsPerPage = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String v) { userAgent = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
}
