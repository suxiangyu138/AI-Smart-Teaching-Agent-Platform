package com.chatbot.model;

import java.util.List;

/**
 * 前端 → 后端 统一请求体（支持 K12+大学 四层学段体系）
 *
 * @author suxiangyu
 */
public class UnifiedChatRequest {

    /** 学段常量：小学（1-6年级） */
    public static final String STAGE_PRIMARY = "primary";
    /** 学段常量：初中（7-9年级） */
    public static final String STAGE_JUNIOR = "junior";
    /** 学段常量：高中（高一~高三） */
    public static final String STAGE_SENIOR = "senior";
    /** 学段常量：大学拓展 */
    public static final String STAGE_UNIVERSITY = "university";

    /** 学段排序权重（用于过滤 ≤ 当前级别） */
    public static final java.util.Map<String, Integer> STAGE_ORDER = java.util.Map.of(
            STAGE_PRIMARY, 1, STAGE_JUNIOR, 2, STAGE_SENIOR, 3, STAGE_UNIVERSITY, 4
    );

    /** 学段 → 推荐 temperature 映射 */
    public static final java.util.Map<String, Double> STAGE_TEMPERATURE = java.util.Map.of(
            STAGE_PRIMARY, 0.1, STAGE_JUNIOR, 0.15, STAGE_SENIOR, 0.2, STAGE_UNIVERSITY, 0.3
    );

    /** @deprecated 保留向后兼容，新代码使用 STAGE_JUNIOR */
    @Deprecated
    public static final String GRADE_JUNIOR = STAGE_JUNIOR;
    /** @deprecated 保留向后兼容，新代码使用 STAGE_SENIOR */
    @Deprecated
    public static final String GRADE_SENIOR = STAGE_SENIOR;

    private String provider;
    private String modelName;
    private String apiKey;
    private String baseUrl;
    private Double temperature = 0.2;
    private Integer maxTokens = 4096;
    private List<Message> messages;
    /** 学段：primary / junior / senior / university */
    private String stage;
    /** 是否允许大学拓展知识 */
    private boolean allowUniversityExtend;
    /** 是否开启 RAG 知识库增强 */
    private boolean ragEnabled;
    /** RAG 检索数量 */
    private int ragTopK = 4;

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
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public boolean isAllowUniversityExtend() { return allowUniversityExtend; }
    public void setAllowUniversityExtend(boolean v) { allowUniversityExtend = v; }
    public boolean isRagEnabled() { return ragEnabled; }
    public void setRagEnabled(boolean v) { ragEnabled = v; }
    public int getRagTopK() { return ragTopK; }
    public void setRagTopK(int v) { ragTopK = v; }

    /** @deprecated 使用 getStage() */
    @Deprecated
    public String getGrade() { return stage; }
    /** @deprecated 使用 setStage() */
    @Deprecated
    public void setGrade(String v) { stage = v; }

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
