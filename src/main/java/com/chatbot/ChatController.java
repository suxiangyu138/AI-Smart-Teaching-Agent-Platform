package com.chatbot;

import com.chatbot.history.ChatHistoryService;
import com.chatbot.model.UnifiedChatRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * 多厂商统一 SSE 控制器 + 对话历史管理
 *
 * @author suxiangyu
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final ChatHistoryService historyService;

    public ChatController(ChatService chatService, ChatHistoryService historyService) {
        this.chatService = chatService;
        this.historyService = historyService;
    }

    /** 统一 SSE 流式聊天（支持数据库会话持久化） */
    @PostMapping(value = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @RequestBody UnifiedChatRequest req,
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        String sessionId = sid.isBlank()
                ? chatService.createSession() : sid;
        // 前端传来的数据库会话ID
        Long dbSessionId = req.getSessionId();
        return chatService.chat(sessionId, dbSessionId, req);
    }

    /** 获取内存会话历史 */
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

    // ========== 对话历史持久化接口（sid cookie 作为会话归属标识） ==========

    /** 创建数据库会话（无 sid cookie 的客户端由服务端生成归属并回写 cookie） */
    @PostMapping("/history/session/create")
    public Map<String, Object> createDbSession(@RequestBody Map<String, String> body,
            @CookieValue(value = "sid", defaultValue = "") String sid,
            HttpServletResponse response) {
        String effectiveSid = sid;
        if (effectiveSid.isBlank()) {
            effectiveSid = UUID.randomUUID().toString();
            response.addHeader("Set-Cookie", "sid=" + effectiveSid + "; Path=/");
        }
        Long id = historyService.createSession(
                body.get("title"), body.get("stage"), body.get("modelName"), effectiveSid);
        return Map.of("sessionId", id);
    }

    /** 查询历史会话列表（仅返回归属当前 sid 的会话） */
    @GetMapping("/history/sessions")
    public List<Map<String, Object>> listSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        return historyService.listSessions(page, size, sid);
    }

    /** 删除会话（逻辑删除，仅归属会话可删） */
    @DeleteMapping("/history/session/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable Long sessionId,
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        historyService.deleteSession(sessionId, sid);
        return Map.of("status", "ok");
    }

    /** 重命名会话（仅归属会话可改） */
    @PutMapping("/history/session/rename")
    public Map<String, String> renameSession(@RequestBody Map<String, Object> body,
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        Long id = Long.valueOf(body.get("sessionId").toString());
        String title = (String) body.get("title");
        historyService.renameSession(id, title, sid);
        return Map.of("status", "ok");
    }

    /** 获取会话全部消息（非归属会话返回空） */
    @GetMapping("/history/messages/{sessionId}")
    public List<Map<String, String>> getMessages(@PathVariable Long sessionId,
            @CookieValue(value = "sid", defaultValue = "") String sid) {
        return historyService.getMessages(sessionId, sid);
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
