package com.chatbot.history;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 对话历史持久化服务 — 管理会话和消息的数据库操作
 */
@Service
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;

    public ChatHistoryService(ChatSessionRepository sessionRepo, ChatMessageRepository messageRepo) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
    }

    /** 创建新会话，返回 sessionId */
    @Transactional
    public Long createSession(String title, String stage, String modelName) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setSessionTitle(title != null ? title : "新对话");
        session.setStageType(stage != null ? stage : "junior");
        session.setModelName(modelName != null ? modelName : "");
        return sessionRepo.save(session).getSessionId();
    }

    /** 分页查询会话列表（按更新时间倒序，排除已删除） */
    public List<Map<String, Object>> listSessions(int page, int size) {
        List<ChatSessionEntity> sessions = sessionRepo
                .findByDeletedFalseOrderByUpdateTimeDesc(PageRequest.of(page, size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatSessionEntity s : sessions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("title", s.getSessionTitle());
            m.put("stage", s.getStageType());
            m.put("modelName", s.getModelName());
            m.put("createTime", s.getCreateTime());
            m.put("updateTime", s.getUpdateTime());
            result.add(m);
        }
        return result;
    }

    /** 保存一条消息（含思考过程） */
    @Transactional
    public void saveMessage(Long sessionId, String role, String content) {
        saveMessage(sessionId, role, content, null);
    }

    @Transactional
    public void saveMessage(Long sessionId, String role, String content, String thinkRaw) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setRawContent(content);
        if (thinkRaw != null && !thinkRaw.isEmpty()) {
            msg.setThinkRaw(thinkRaw);
        }
        messageRepo.save(msg);
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setUpdateTime(java.time.LocalDateTime.now());
            if (("新对话".equals(s.getSessionTitle()) || s.getSessionTitle() == null)
                    && "user".equals(role)) {
                String title = content.length() > 18 ? content.substring(0, 18) : content;
                s.setSessionTitle(title.replace("\n", " ").trim());
            }
            sessionRepo.save(s);
        });
    }

    /** 获取某会话的全部消息 */
    public List<Map<String, String>> getMessages(Long sessionId) {
        List<ChatMessageEntity> msgs = messageRepo.findBySessionIdOrderByCreateTimeAsc(sessionId);
        List<Map<String, String>> result = new ArrayList<>();
        for (ChatMessageEntity m : msgs) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", m.getRole());
            item.put("content", m.getRawContent());
            if (m.getThinkRaw() != null && !m.getThinkRaw().isEmpty()) {
                item.put("thinkRaw", m.getThinkRaw());
            }
            item.put("time", m.getCreateTime() != null ? m.getCreateTime().toString() : "");
            result.add(item);
        }
        return result;
    }

    /** 逻辑删除会话 */
    @Transactional
    public void deleteSession(Long sessionId) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setDeleted(true);
            sessionRepo.save(s);
        });
    }

    /** 重命名会话 */
    @Transactional
    public void renameSession(Long sessionId, String newTitle) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setSessionTitle(newTitle);
            sessionRepo.save(s);
        });
    }

    /** 更新会话模型/学段信息 */
    @Transactional
    public void updateSessionMeta(Long sessionId, String stage, String modelName) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            if (stage != null) s.setStageType(stage);
            if (modelName != null) s.setModelName(modelName);
            sessionRepo.save(s);
        });
    }
}
