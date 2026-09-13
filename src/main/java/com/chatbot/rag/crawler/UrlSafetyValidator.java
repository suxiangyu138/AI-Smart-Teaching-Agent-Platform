package com.chatbot.rag.crawler;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 抓取目标地址安全校验。
 * <p>
 * 爬虫会把抓到的内容写进知识库，等于给调用方提供了一个「让服务器替我发请求」
 * 的通道（SSRF）。因此凡是要真正发起网络请求的地址，都必须先过这里。
 * <p>
 * 校验两层：
 * <ol>
 *   <li>协议只放行 http/https（Jsoup 自身也只支持这两种，这里提前拦下给出可读报错）；</li>
 *   <li>主机解析出的每一个 IP 都不能是回环 / 内网 / 链路本地（含云厂商元数据
 *       169.254.169.254）——按解析结果而非字符串判断，避免
 *       {@code localhost}、十进制 IP 之类的写法绕过。</li>
 * </ol>
 * <p>
 * 注意：这里刻意做成「每跳都校验」。只在入口校验一次是不够的，
 * 目标站点返回 {@code 302 Location: http://127.0.0.1:9200/} 就能把请求带进内网。
 *
 * @author suxiangyu
 */
public final class UrlSafetyValidator {

    private UrlSafetyValidator() {
    }

    /**
     * 校验一个将要被抓取的地址。
     *
     * @param url 待校验地址
     * @return {@code null} 表示通过，否则返回可直接展示给调用方的中文错误原因
     */
    public static String check(String url) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "URL 必须以 http:// 或 https:// 开头";
        }
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "URL 缺少主机名";
            }
            // 注意：这里用解析后的 IP 判断，域名指向内网同样会被拦下。
            // 代价是一次 DNS 查询；解析结果可能与真正连接时不同（DNS rebinding），
            // 彻底解决需要把校验过的 IP 固定到实际连接上，当前未做到。
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isInternalAddress(addr)) {
                    return "不允许爬取内网或本机地址: " + host;
                }
            }
        } catch (IllegalArgumentException e) {
            return "URL 格式不正确";
        } catch (UnknownHostException e) {
            return "无法解析主机名";
        }
        return null;
    }

    /** 判断是否为回环 / 任意本地 / 链路本地 / 内网 / IPv6 ULA 地址 */
    public static boolean isInternalAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7（JDK 未提供现成判断）
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
