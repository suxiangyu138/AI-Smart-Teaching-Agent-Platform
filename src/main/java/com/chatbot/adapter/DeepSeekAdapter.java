package com.chatbot.adapter;

import com.chatbot.model.UnifiedChatRequest;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * DeepSeek — 原生 OpenAI 兼容
 *
 * @author suxiangyu
 */
@Component
public class DeepSeekAdapter extends BaseModelAdapter {

    @Override
    public String getProviderCode() { return "deepseek"; }

    @Override
    protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        return buildOpenAiBody(req, "https://api.deepseek.com/v1");
    }

    static String buildOpenAiBody(UnifiedChatRequest req, String defaultBase)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", req.getModelName());
        root.put("stream", true);
        root.put("temperature", req.getTemperature());
        root.put("max_tokens", req.getMaxTokens());
        ArrayNode msgs = MAPPER.createArrayNode();
        if (req.getMessages() != null) {
            for (var m : req.getMessages()) {
                ObjectNode o = MAPPER.createObjectNode();
                o.put("role", m.getRole());
                o.put("content", m.getContent());
                msgs.add(o);
            }
        }
        root.set("messages", msgs);
        return MAPPER.writeValueAsString(root);
    }
}
