package com.chatbot;

import com.chatbot.adapter.BaseModelAdapter;
import com.chatbot.adapter.ModelAdapterFactory;
import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.model.UnifiedStreamChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多厂商会话管理 + 统一流式推送
 *
 * @author suxiangyu
 */
@Service
public class ChatService {

    private final ModelAdapterFactory adapterFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<ChatMessage>> sessions
            = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    public ChatService(ModelAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
    }

    /** 并发流式聊天 */
    @SuppressWarnings("null")
    public SseEmitter chat(String sessionId,
                           UnifiedChatRequest req) {
        List<ChatMessage> history = getSession(sessionId);
        history.add(new ChatMessage(ChatMessage.Role.USER,
                lastUserContent(req)));

        // 构建 messages
        List<UnifiedChatRequest.Message> msgs = new ArrayList<>();
        msgs.add(new UnifiedChatRequest.Message("system",
                new ConfigManager().getSystemPrompt()));
        for (ChatMessage m : history) {
            msgs.add(new UnifiedChatRequest.Message(
                    m.getRole() == ChatMessage.Role.USER
                            ? "user" : "assistant",
                    m.getContent()));
        }
        req.setMessages(msgs);

        BaseModelAdapter adapter = adapterFactory.getAdapter(
                req.getProvider() != null
                        ? req.getProvider() : "deepseek");

        SseEmitter emitter = new SseEmitter(180_000L);
        StringBuilder full = new StringBuilder();

        adapter.streamChat(req,
                chunk -> {
                    full.append(
                            chunk.getContent() != null
                                    ? chunk.getContent() : "");
                    try {
                        emitter.send(SseEmitter.event()
                                .name("chunk")
                                .data(mapper.writeValueAsString(chunk)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(mapper.writeValueAsString(
                                        UnifiedStreamChunk.error(
                                                error.getMessage()))));
                        emitter.complete();
                    } catch (IOException ex) {
                        emitter.completeWithError(ex);
                    }
                },
                () -> {
                    history.add(new ChatMessage(
                            ChatMessage.Role.ASSISTANT,
                            full.toString()));
                    trimHistory(history);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data(mapper.writeValueAsString(
                                        UnifiedStreamChunk.done(
                                                full.toString()))));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });

        return emitter;
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
        return sessions.computeIfAbsent(sessionId,
                k -> new ArrayList<>());
    }

    private void trimHistory(List<ChatMessage> h) {
        while (h.size() > MAX_HISTORY) {
            h.removeFirst();
        }
    }
}
