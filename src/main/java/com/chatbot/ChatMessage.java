package com.chatbot;

/**
 * 聊天消息模型
 *
 * @author suxiangyu
 */
public class ChatMessage {

    /** 消息角色 */
    public enum Role {
        /** 用户消息 */
        USER,
        /** AI回复 */
        ASSISTANT,
        /** 系统提示 */
        SYSTEM
    }

    private final Role role;
    private final String content;
    private final long timestamp;

    /** 是否正在流式输出中 */
    private boolean isStreaming;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
        this.isStreaming = false;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public void setStreaming(boolean streaming) {
        isStreaming = streaming;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s", role, content);
    }
}
