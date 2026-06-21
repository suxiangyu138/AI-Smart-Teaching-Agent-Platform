package com.chatbot;

import com.chatbot.model.UnifiedChatRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * 多厂商统一 SSE 控制器
 *
 * @author suxiangyu
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 统一 SSE 流式聊天 */
    @PostMapping(value = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestBody UnifiedChatRequest req,
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        String sessionId = sid.isBlank()
                ? chatService.createSession() : sid;
        return chatService.chat(sessionId, req);
    }

    /** 获取会话历史 */
    @GetMapping("/history")
    public List<ChatMessage> history(
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        return sid.isBlank()
                ? List.of() : chatService.getHistory(sid);
    }

    /** 清空会话 */
    @DeleteMapping("/chat")
    public Map<String, String> clear(
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        chatService.clearSession(sid);
        return Map.of("status", "ok");
    }

    /** 生成新会话 */
    @GetMapping("/session")
    public Map<String, String> session() {
        return Map.of("sid", chatService.createSession());
    }

    /** 获取所有厂商 + 模型列表 */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return List.of(
                providerMap("deepseek", "深度求索 DeepSeek",
                        "https://api.deepseek.com/v1",
                        List.of("deepseek-v4-pro", "deepseek-v4-flash",
                                "deepseek-v3.2", "deepseek-r1")),
                providerMap("zhipu", "智谱AI GLM",
                        "https://open.bigmodel.cn/api/paas/v4",
                        List.of("glm-5.2", "glm-5-turbo",
                                "glm-4.7-flash", "glm-4-flash")),
                providerMap("qwen", "阿里通义千问",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        List.of("qwen3.7-max", "qwen-max", "qwen-plus",
                                "qwen-long")),
                providerMap("moonshot", "Moonshot Kimi",
                        "https://api.moonshot.cn/v1",
                        List.of("kimi-k2.6", "kimi-k2.5",
                                "moonshot-v1-128k")),
                providerMap("minimax", "MiniMax 稀宇",
                        "https://api.minimaxi.com/v1",
                        List.of("MiniMax-M3", "MiniMax-M2.7",
                                "MiniMax-M2.7-highspeed")),
                providerMap("mimo", "小米 MiMo",
                        "https://api.xiaomimimo.com/v1",
                        List.of("mimo-v2.5-pro", "mimo-v2-pro",
                                "mimo-v2-flash"))
        );
    }

    private Map<String, Object> providerMap(
            String code, String name, String baseUrl,
            List<String> models) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", code);
        m.put("providerName", name);
        m.put("baseUrl", baseUrl);
        m.put("modelOptions", models.stream()
                .map(v -> Map.of("label", v, "value", v)).toList());
        return m;
    }
}
