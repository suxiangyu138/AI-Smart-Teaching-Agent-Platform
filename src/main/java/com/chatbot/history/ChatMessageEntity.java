package com.chatbot.history;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 消息明细表 — 一条消息 = 一次用户提问 或 一次AI完整回复
 */
@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long msgId;

    @Column(nullable = false)
    private Long sessionId;

    @Column(length = 10, nullable = false)
    private String role;  // "user" | "assistant"

    @Column(columnDefinition = "CLOB")
    private String rawContent;

    @Column(columnDefinition = "CLOB")
    private String thinkRaw;

    private LocalDateTime createTime;

    @PrePersist
    void onCreate() {
        createTime = LocalDateTime.now();
    }

    public Long getMsgId() { return msgId; }
    public void setMsgId(Long v) { msgId = v; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long v) { sessionId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { role = v; }
    public String getRawContent() { return rawContent; }
    public void setRawContent(String v) { rawContent = v; }
    public String getThinkRaw() { return thinkRaw; }
    public void setThinkRaw(String v) { thinkRaw = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime v) { createTime = v; }
}
