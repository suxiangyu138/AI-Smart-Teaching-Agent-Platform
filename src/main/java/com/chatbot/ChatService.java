package com.chatbot;

import com.chatbot.adapter.BaseModelAdapter;
import com.chatbot.adapter.ModelAdapterFactory;
import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.model.UnifiedStreamChunk;
import com.chatbot.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多厂商会话管理 + 统一流式推送（集成 RAG 知识库增强）
 *
 * @author suxiangyu
 */
@Service
@SuppressWarnings("null")
public class ChatService {

    private final ModelAdapterFactory adapterFactory;
    private final RagService ragService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    public ChatService(ModelAdapterFactory adapterFactory, RagService ragService) {
        this.adapterFactory = adapterFactory;
        this.ragService = ragService;
    }

    /** 并发流式聊天 */
    public SseEmitter chat(String sessionId, UnifiedChatRequest req) {
        List<ChatMessage> hist = getSession(sessionId);
        hist.add(new ChatMessage(ChatMessage.Role.USER, lastUserContent(req)));

        req.setMessages(buildMessages(req, hist));

        BaseModelAdapter adapter = adapterFactory.getAdapter(
                req.getProvider() != null ? req.getProvider() : "deepseek");

        SseEmitter em = new SseEmitter(180_000L);
        StringBuilder buf = new StringBuilder();

        adapter.streamChat(req,
                chunk -> onChunk(em, chunk, buf),
                err -> onError(em, err),
                () -> onComplete(em, hist, buf));

        return em;
    }

    /** 构建系统提示词（含四层学段适配 + 大学拓展 + RAG 增强） */
    private String buildSystemPrompt(UnifiedChatRequest req) {
        String stage = req.getStage() != null ? req.getStage() : UnifiedChatRequest.STAGE_JUNIOR;
        String prompt = new ConfigManager().getSystemPrompt();
        prompt += buildStageDirective(stage, req.isAllowUniversityExtend());

        if (req.isRagEnabled()) {
            String ctx = ragService.buildRagContext(lastUserContent(req), req,
                    req.getRagTopK() > 0 ? req.getRagTopK() : 4);
            if (!ctx.isEmpty()) {
                prompt += ctx;
            }
        }
        return prompt;
    }

    /** 按学段拼接专属指令 */
    private String buildStageDirective(String stage, boolean allowExtend) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");

        if (UnifiedChatRequest.STAGE_PRIMARY.equals(stage)) {
            sb.append("当前学段：小学（1-6年级）。请使用小学数学知识体系。\n");
            sb.append("约束：无复杂证明，步骤通俗，多用生活化举例，拒绝抽象符号。\n");
            sb.append("禁止使用初中及以上公式定理（如二次函数、三角函数、导数）。\n");
            sb.append("若学生提问超纲内容，先用生活化方式简化解释，");
            sb.append("再提示可切换至初中学段获取标准解法。\n");
        } else if (UnifiedChatRequest.STAGE_JUNIOR.equals(stage)) {
            sb.append("当前学段：初中（7-9年级）。请使用初中数学知识体系，难度不超过中考水平。\n");
            sb.append("可使用：一次/二次函数、平面几何、三角形/圆、不等式、二元方程、基础概率统计。\n");
            sb.append("禁止使用高中工具（导数、向量、圆锥曲线、立体几何）解答初中题目。\n");
            if (!allowExtend) {
                sb.append("遇到学生追问更深原理时，提示可切换高中模式或开启拓展模式。\n");
            }
        } else if (UnifiedChatRequest.STAGE_SENIOR.equals(stage)) {
            sb.append("当前学段：高中（高一~高三）。请使用高中数学知识体系，难度不超过高考水平。\n");
            sb.append("完整覆盖：导数、圆锥曲线、数列、立体几何、向量、三角函数、排列组合、概率分布。\n");
            if (!allowExtend) {
                sb.append("不主动引入大学微积分严格证明、ε-δ语言、矩阵理论。如需拓展请开启拓展模式。\n");
            }
        } else if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(stage)) {
            sb.append("当前学段：大学拓展模式。可使用微积分进阶、线性代数、离散数学、");
            sb.append("概率论数理统计、初等数论等大学数学知识。\n");
        }

        if (allowExtend) {
            sb.append("「大学拓展模式」已开启。可在课内标准解法之外，");
            sb.append("补充大学数学视角的底层原理推导，请明确标注「📚拓展知识（超K12课内，选学）」。\n");
            sb.append("输出时请分层：先给课内标准答案，再附拓展内容。\n");
        }

        sb.append("【LaTeX公式规则 - 必须区分行内/块级】");
        sb.append("行内公式（句子中的数字、符号、短方程）用单个 $ 包裹，如 $x^2$、$f'(x)$、$-10$，必须和文字同行！");
        sb.append("块级公式（独立展示的大公式、多行推导）才用 $$...$$ 居中。");
        sb.append("禁止裸写任何数学符号，禁止将行内公式换行拆分。\n");

        return sb.toString();
    }

    /** 构建消息列表 */
    private List<UnifiedChatRequest.Message> buildMessages(
            UnifiedChatRequest req, List<ChatMessage> hist) {
        List<UnifiedChatRequest.Message> msgs = new ArrayList<>();
        msgs.add(new UnifiedChatRequest.Message("system", buildSystemPrompt(req)));

        // 按学段设置推荐温度
        String stage = req.getStage() != null ? req.getStage() : UnifiedChatRequest.STAGE_JUNIOR;
        Double stageTemp = UnifiedChatRequest.STAGE_TEMPERATURE.get(stage);
        if (stageTemp != null && req.getTemperature() == null) {
            req.setTemperature(stageTemp);
        }

        for (ChatMessage m : hist) {
            String role = m.getRole() == ChatMessage.Role.USER ? "user" : "assistant";
            msgs.add(new UnifiedChatRequest.Message(role, m.getContent()));
        }
        return msgs;
    }

    /** 构建 SSE 事件 */
    private static SseEmitter.SseEventBuilder sseEvent(String name, String data) {
        return SseEmitter.event().name(name).data(data);
    }

    private void onChunk(SseEmitter em, UnifiedStreamChunk chunk, StringBuilder buf) {
        buf.append(chunk.getContent() != null ? chunk.getContent() : "");
        try {
            em.send(sseEvent("chunk", mapper.writeValueAsString(chunk)));
        } catch (IOException e) {
            em.completeWithError(e);
        }
    }

    private void onError(SseEmitter em, Throwable err) {
        try {
            em.send(sseEvent("error", mapper.writeValueAsString(
                    UnifiedStreamChunk.error(err.getMessage()))));
            em.complete();
        } catch (IOException ex) {
            em.completeWithError(ex);
        }
    }

    private void onComplete(SseEmitter em, List<ChatMessage> hist, StringBuilder buf) {
        hist.add(new ChatMessage(ChatMessage.Role.ASSISTANT, buf.toString()));
        trimHistory(hist);
        try {
            em.send(sseEvent("finish", mapper.writeValueAsString(
                    UnifiedStreamChunk.finish(buf.toString()))));
            em.complete();
        } catch (IOException e) {
            em.completeWithError(e);
        }
    }

    private String lastUserContent(UnifiedChatRequest req) {
        if (req.getMessages() != null && !req.getMessages().isEmpty()) {
            return req.getMessages().getLast().getContent();
        }
        return "";
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return getSession(sessionId);
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public String createSession() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private List<ChatMessage> getSession(String sessionId) {
        return sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    private void trimHistory(List<ChatMessage> h) {
        while (h.size() > MAX_HISTORY) {
            h.removeFirst();
        }
    }
}
