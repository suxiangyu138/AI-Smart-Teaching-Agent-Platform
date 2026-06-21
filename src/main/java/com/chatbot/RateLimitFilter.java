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
 *
 * @author suxiangyu
 */
@Component
public class RateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final String CHAT_PATH = "/api/chat";
    private final Map<String, long[]> counters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String path = request.getRequestURI();

        if (!CHAT_PATH.equals(path)) {
            chain.doFilter(req, resp);
            return;
        }

        String ip = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;

        counters.compute(ip, (k, v) -> {
            if (v == null) {
                return new long[]{ now, 1 };
            }
            if (v[0] < windowStart) {
                return new long[]{ now, 1 };
            }
            v[1]++;
            return v;
        });

        long[] entry = counters.get(ip);
        if (entry[1] > MAX_REQUESTS_PER_MINUTE) {
            HttpServletResponse httpResp = (HttpServletResponse) resp;
            httpResp.setStatus(429);
            httpResp.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
            return;
        }
        chain.doFilter(req, resp);
    }
}
