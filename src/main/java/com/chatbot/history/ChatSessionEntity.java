package com.chatbot.history;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 会话主表 — 一次完整对话对应一条记录
 */
@Entity
@Table(name = "chat_session")
public class ChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    @Column(length = 200)
    private String sessionTitle;

    @Column(length = 20)
    private String stageType;

    @Column(length = 50)
    private String modelName;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Column(nullable = false)
    private boolean deleted = false;

    @PrePersist
    void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long v) { sessionId = v; }
    public String getSessionTitle() { return sessionTitle; }
    public void setSessionTitle(String v) { sessionTitle = v; }
    public String getStageType() { return stageType; }
    public void setStageType(String v) { stageType = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { modelName = v; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime v) { createTime = v; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime v) { updateTime = v; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean v) { deleted = v; }
}
