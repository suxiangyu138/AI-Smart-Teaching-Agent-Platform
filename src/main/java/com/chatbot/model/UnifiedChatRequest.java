package com.chatbot.model;

import java.util.List;

/**
 * 前端 → 后端 统一请求体
 *
 * @author suxiangyu
 */
public class UnifiedChatRequest {
    private String provider;
    private String modelName;
    private String apiKey;
    private String baseUrl;
    private Double temperature = 0.7;
    private Integer maxTokens = 4096;
    private List<Message> messages;

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { modelName = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double v) { temperature = v; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer v) { maxTokens = v; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> v) { messages = v; }

    public static class Message {
        private String role;
        private String content;
        public Message() {}
        public Message(String role, String content) {
            this.role = role; this.content = content;
        }
        public String getRole() { return role; }
        public void setRole(String v) { role = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
    }
}
