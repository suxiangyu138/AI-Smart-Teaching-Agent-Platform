package com.chatbot;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易限流：每 IP 每分钟最多 N 次请求
 * <p>
 * 分两档：对话接口按 30 次/分钟（调用大模型，成本高）；
 * 其余 /api 接口按 300 次/分钟——前端索引期间会以 1 秒间隔轮询
 * /api/knowledge/progress（60 次/分钟），阈值必须留足余量。
 * 静态资源不限制。
 *
 * @author suxiangyu
 */
@Component
public class RateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final int MAX_OTHER_API_PER_MINUTE = 300;
    private static final String CHAT_PATH = "/api/chat";
    private static final String API_PREFIX = "/api/";
    private static final long WINDOW_MS = 60_000;
    /** 清理过期计数的最小间隔，避免每次请求都遍历 map */
    private static final long SWEEP_INTERVAL_MS = 60_000;

    private final Map<String, long[]> counters = new ConcurrentHashMap<>();
    private volatile long lastSweep = System.currentTimeMillis();

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String path = request.getRequestURI();

        // 非 API 请求（静态页面、KaTeX/marked 等资源）不限流
        if (!path.startsWith(API_PREFIX)) {
            chain.doFilter(req, resp);
            return;
        }

        boolean isChat = CHAT_PATH.equals(path);
        int limit = isChat ? MAX_REQUESTS_PER_MINUTE : MAX_OTHER_API_PER_MINUTE;

        String ip = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        sweepIfDue(now, windowStart);

        // 计数器必须按「IP + 档位」分开。若只按 IP 建键，
        // 索引期间每秒一次的知识库轮询会把对话接口的 30 次额度吃掉，
        // 导致正常提问被 429。
        String key = ip + (isChat ? "|chat" : "|api");

        long[] entry = counters.compute(key, (k, v) -> {
            if (v == null) {
                return new long[]{ now, 1 };
            }
            if (v[0] < windowStart) {
                return new long[]{ now, 1 };
            }
            v[1]++;
            return v;
        });

        if (entry[1] > limit) {
            HttpServletResponse httpResp = (HttpServletResponse) resp;
            httpResp.setStatus(429);
            httpResp.setContentType("application/json;charset=UTF-8");
            httpResp.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
            return;
        }
        chain.doFilter(req, resp);
    }

    /**
     * 周期性移除已过期的计数器，防止 map 随访问 IP 数无限增长。
     */
    private void sweepIfDue(long now, long windowStart) {
        if (now - lastSweep < SWEEP_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - lastSweep < SWEEP_INTERVAL_MS) {
                return;
            }
            counters.entrySet().removeIf(e -> e.getValue()[0] < windowStart);
            lastSweep = now;
        }
    }
}
