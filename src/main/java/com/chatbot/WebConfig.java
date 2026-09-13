package com.chatbot;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 CORS 配置
 * <p>
 * 只放行本机来源。前端页面由本服务同源提供，本身不需要 CORS；
 * 这里保留 localhost/127.0.0.1 是为了本地分端口调试的便利。
 * 切勿改成 allowedOrigins("*")：那会让任意网站在用户浏览器里
 * 直接读写本机这个无认证的服务。
 *
 * @author suxiangyu
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*");
    }
}
