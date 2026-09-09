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

    /** 创建新会话，返回 sessionId（会话必须有归属，sid 为空则服务端生成） */
    @Transactional
    public Long createSession(String title, String stage, String modelName, String ownerSid) {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setSessionTitle(title != null ? title : "新对话");
        session.setStageType(stage != null ? stage : "junior");
        session.setModelName(modelName != null ? modelName : "");
        session.setOwnerSid(ownerSid != null && !ownerSid.isBlank()
                ? ownerSid : UUID.randomUUID().toString());
        return sessionRepo.save(session).getSessionId();
    }

    /** 会话归属校验：归属必须存在且匹配 sid。历史遗留的无归属会话对任何客户端不可见 */
    public boolean isSessionOwned(Long sessionId, String sid) {
        return sessionRepo.findById(sessionId)
                .map(s -> s.getOwnerSid() != null && s.getOwnerSid().equals(sid))
                .orElse(false);
    }

    /** 分页查询会话列表（按更新时间倒序，排除已删除；仅返回归属当前 sid 或历史遗留的会话） */
    public List<Map<String, Object>> listSessions(int page, int size, String sid) {
        List<ChatSessionEntity> sessions = sessionRepo
                .findByDeletedFalseOrderByUpdateTimeDesc(PageRequest.of(page, size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatSessionEntity s : sessions) {
            if (s.getOwnerSid() == null || !s.getOwnerSid().equals(sid)) {
                continue; // 无归属（历史遗留）或其他客户端的会话不可见
            }
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

    /** 获取某会话的全部消息（非归属会话返回空列表） */
    public List<Map<String, String>> getMessages(Long sessionId, String sid) {
        if (!isSessionOwned(sessionId, sid)) {
            return List.of();
        }
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

    /** 逻辑删除会话（仅归属会话可删） */
    @Transactional
    public void deleteSession(Long sessionId, String sid) {
        if (!isSessionOwned(sessionId, sid)) {
            return;
        }
        sessionRepo.findById(sessionId).ifPresent(s -> {
            s.setDeleted(true);
            sessionRepo.save(s);
        });
    }

    /** 重命名会话（仅归属会话可改） */
    @Transactional
    public void renameSession(Long sessionId, String newTitle, String sid) {
        if (!isSessionOwned(sessionId, sid)) {
            return;
        }
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
