package com.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置管理：API Key 持久化存储
 *
 * @author suxiangyu
 */
@Component
public class ConfigManager {

    private static final Path CONFIG_DIR = Paths.get(
            System.getProperty("user.home"), ".deepseek-chatbot");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private final ObjectMapper mapper;
    private Map<String, Object> config;

    public ConfigManager() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.config = new LinkedHashMap<>();
        loadConfig();
    }

    /**
     * 加载配置文件
     */
    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> loaded = mapper.readValue(
                        CONFIG_FILE.toFile(), Map.class);
                config = loaded;
            }
        } catch (IOException e) {
            System.err.println("加载配置失败: " + e.getMessage());
            config = new LinkedHashMap<>();
        }
    }

    /**
     * 保存配置
     */
    public void saveConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), config);
        } catch (IOException e) {
            System.err.println("保存配置失败: " + e.getMessage());
        }
    }

    public String getApiKey() {
        Object key = config.get("apiKey");
        return key != null ? key.toString() : "";
    }

    public void setApiKey(String apiKey) {
        config.put("apiKey", apiKey);
        saveConfig();
    }

    public String getApiEndpoint() {
        Object endpoint = config.get("apiEndpoint");
        return endpoint != null ? endpoint.toString()
                : "https://api.deepseek.com/v1/chat/completions";
    }

    public void setApiEndpoint(String endpoint) {
        config.put("apiEndpoint", endpoint);
        saveConfig();
    }

    public String getModel() {
        Object model = config.get("model");
        return model != null ? model.toString() : "deepseek-chat";
    }

    public void setModel(String model) {
        config.put("model", model);
        saveConfig();
    }

    public String getSystemPrompt() {
        Object prompt = config.get("systemPrompt");
        return prompt != null ? prompt.toString()
                : "你是一个有用的AI助手，用简洁清晰的中文回答用户问题。";
    }

    public void setSystemPrompt(String prompt) {
        config.put("systemPrompt", prompt);
        saveConfig();
    }

    /**
     * API Key 是否已配置
     */
    public boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.isBlank();
    }
}
