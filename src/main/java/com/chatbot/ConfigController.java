package com.chatbot;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 配置 API — 管理 API Key 等设置
 *
 * @author suxiangyu
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SYSTEM_PROMPT = "systemPrompt";

    private final ConfigManager config;

    public ConfigController(ConfigManager config) {
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> getConfig() {
        return Map.of(
                "hasApiKey", config.hasApiKey(),
                KEY_MODEL, config.getModel(),
                "endpoint", config.getApiEndpoint(),
                KEY_SYSTEM_PROMPT, config.getSystemPrompt()
        );
    }

    @PostMapping
    public Map<String, String> saveConfig(
            @RequestBody Map<String, String> body) {
        String apiKey = body.get(KEY_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            config.setApiKey(apiKey);
        }
        if (body.containsKey(KEY_MODEL)) {
            config.setModel(body.get(KEY_MODEL));
        }
        if (body.containsKey(KEY_SYSTEM_PROMPT)) {
            config.setSystemPrompt(body.get(KEY_SYSTEM_PROMPT));
        }
        return Map.of("status", "ok");
    }
}
