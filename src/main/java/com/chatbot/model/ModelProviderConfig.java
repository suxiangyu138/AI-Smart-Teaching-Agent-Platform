package com.chatbot.model;

/**
 * 厂商配置
 *
 * @author suxiangyu
 */
public class ModelProviderConfig {
    private String provider;
    private String providerName;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Double temperature = 0.7;
    private Integer maxTokens = 4096;

    public ModelProviderConfig() {}

    public ModelProviderConfig(String provider, String providerName,
                               String baseUrl) {
        this.provider = provider;
        this.providerName = providerName;
        this.baseUrl = baseUrl;
    }

    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String v) { providerName = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { modelName = v; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double v) { temperature = v; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer v) { maxTokens = v; }
}
