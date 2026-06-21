package com.chatbot.adapter;

import com.chatbot.model.UnifiedChatRequest;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Moonshot Kimi — OpenAI 兼容协议
 *
 * 注意：kimi-k2.x 推理模型只接受 temperature=1，moonshot-v1 则无此限制。
 * 为兼容所有模型，干脆不传 temperature 字段。
 *
 * @author suxiangyu
 */
@Component
public class MoonshotKimiAdapter extends BaseModelAdapter {

    @Override
    public String getProviderCode() { return "moonshot"; }

    @Override
    protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", req.getModelName());
        root.put("stream", true);
        // 不传 temperature —— kimi-k2.x 只允许 1，传其他值会 400
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
