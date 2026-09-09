package com.chatbot;

import com.chatbot.adapter.BaseModelAdapter;
import com.chatbot.adapter.ModelAdapterFactory;
import com.chatbot.history.ChatHistoryService;
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
public class ChatService {

    private final ModelAdapterFactory adapterFactory;
    private final RagService ragService;
    private final ChatHistoryService historyService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    public ChatService(ModelAdapterFactory adapterFactory, RagService ragService,
                       ChatHistoryService historyService) {
        this.adapterFactory = adapterFactory;
        this.ragService = ragService;
        this.historyService = historyService;
    }

    /** 并发流式聊天（兼容旧 cookie 模式） */
    public SseEmitter chat(String sessionId, UnifiedChatRequest req) {
        return chat(sessionId, null, req);
    }

    /** 并发流式聊天 + 数据库持久化 */
    public SseEmitter chat(String memSessionId, Long dbSessionId, UnifiedChatRequest req) {
        String userContent = lastUserContent(req);

        // 数据库会话：以数据库历史为上下文，保证多会话严格隔离、页面刷新后上下文连续。
        // 归属校验：客户端传来的会话ID必须属于当前 sid，否则降级为内存模式（不读也不写）
        Long effectiveDbId = (dbSessionId != null
                && historyService.isSessionOwned(dbSessionId, memSessionId))
                ? dbSessionId : null;

        List<ChatMessage> hist;
        if (effectiveDbId != null) {
            List<ChatMessage> dbHist = new ArrayList<>();
            List<Map<String, String>> dbMsgs = historyService.getMessages(effectiveDbId, memSessionId);
            int from = Math.max(0, dbMsgs.size() - (MAX_HISTORY - 1)); // 只带最近20条
            for (int i = from; i < dbMsgs.size(); i++) {
                Map<String, String> m = dbMsgs.get(i);
                String content = m.get("content");
                if (content == null || content.isBlank()) {
                    continue;
                }
                ChatMessage.Role role = "user".equals(m.get("role"))
                        ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT;
                dbHist.add(new ChatMessage(role, content));
            }
            hist = dbHist;
            sessions.put(memSessionId, hist); // 同步内存会话，避免与旧内存历史混用
        } else {
            hist = getSession(memSessionId);
        }
        hist.add(new ChatMessage(ChatMessage.Role.USER, userContent));

        req.setMessages(buildMessages(req, hist));

        BaseModelAdapter adapter = adapterFactory.getAdapter(
                req.getProvider() != null ? req.getProvider() : "deepseek");

        SseEmitter em = new SseEmitter(180_000L);
        StringBuilder buf = new StringBuilder();
        StringBuilder thinkBuf = new StringBuilder();

        // 持久化用户消息
        final Long finalDbId = effectiveDbId;
        if (finalDbId != null) {
            historyService.saveMessage(finalDbId, "user", userContent);
            historyService.updateSessionMeta(finalDbId,
                    req.getStage(), req.getModelName());
        }

        adapter.streamChat(req,
                chunk -> onChunk(em, chunk, buf, thinkBuf),
                err -> onError(em, err),
                () -> onComplete(em, hist, buf, thinkBuf, finalDbId));

        return em;
    }

    private void onChunk(SseEmitter em, UnifiedStreamChunk chunk,
                         StringBuilder buf, StringBuilder thinkBuf) {
        if (UnifiedStreamChunk.TYPE_REASONING.equals(chunk.getType())) {
            thinkBuf.append(chunk.getReasoning() != null ? chunk.getReasoning() : "");
        } else {
            buf.append(chunk.getContent() != null ? chunk.getContent() : "");
        }
        try {
            em.send(sseEvent(chunk.getType(), mapper.writeValueAsString(chunk)));
        } catch (IOException e) {
            em.completeWithError(e);
        }
    }

    private void onComplete(SseEmitter em, List<ChatMessage> hist,
                            StringBuilder buf, StringBuilder thinkBuf,
                            Long dbSessionId) {
        String aiContent = buf.toString();
        String thinkContent = thinkBuf.toString();
        hist.add(new ChatMessage(ChatMessage.Role.ASSISTANT, aiContent));
        trimHistory(hist);

        // 持久化AI回复 + 思考过程
        if (dbSessionId != null && !aiContent.isEmpty()) {
            historyService.saveMessage(dbSessionId, "assistant", aiContent, thinkContent);
        }

        try {
            em.send(sseEvent("finish", mapper.writeValueAsString(
                    UnifiedStreamChunk.finish(aiContent, thinkContent))));
            em.complete();
        } catch (IOException e) {
            em.completeWithError(e);
        }
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
            sb.append("补充大学数学视角的底层原理推导，请明确标注「拓展知识（超K12课内，选学）」。\n");
            sb.append("输出时请分层：先给课内标准答案，再附拓展内容。\n");
        }

        sb.append("【LaTeX数学公式强制规范 - 必须严格遵循】\n");
        sb.append("1. 行内公式只用 $...$：如 $a_1$、$x^2$、$S_n$，与文字同行不分段。\n");
        sb.append("2. 块级公式只用 $$...$$：\n");
        sb.append("   $$ 单独占一行，公式写在同一行内，$$ 单独占一行。\n");
        sb.append("   正确格式：\n");
        sb.append("   $$\n");
        sb.append("   a_n = a_1 + (n-1)d\n");
        sb.append("   $$\n");
        sb.append("3. 分式必须用 \\frac{分子}{分母} 完整一行写完，绝不允许把分子分母拆成多行！\n");
        sb.append("   正确：$$S_n = \\frac{n(a_1 + a_n)}{2}$$\n");
        sb.append("   错误：$$S_n = \\frac{n(a_1 + a_n)}{2}$$（分子分母分行写会坏掉）\n");
        sb.append("4. 下标用单下划线 a_1、a_n、x_0，禁止用空格分隔写成 a 1 或 a n。\n");
        sb.append("5. 禁止使用 \\[ \\] 或 \\( \\) 作为公式分隔符，只用 $$ 和 $。\n");
        sb.append("6. 公式内部禁止换行，禁止插入零宽字符、全角空格。\n");
        sb.append("7. 求和符号 \\sum、积分 \\int、极限 \\lim 等必须写在块级公式 $$ 内。\n");

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

    private void onError(SseEmitter em, Throwable err) {
        try {
            em.send(sseEvent("error", mapper.writeValueAsString(
                    UnifiedStreamChunk.error(err.getMessage()))));
            em.complete();
        } catch (IOException ex) {
            em.completeWithError(ex);
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
        // 完整 UUID 作为归属标识：8 位截断易被遍历猜测
        return UUID.randomUUID().toString();
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
