package com.chatbot.adapter;

import com.chatbot.model.UnifiedChatRequest;
import org.springframework.stereotype.Component;

/**
 * MiniMax — OpenAI 兼容协议
 *
 * @author suxiangyu
 */
@Component
public class MiniMaxAdapter extends BaseModelAdapter {

    @Override
    public String getProviderCode() { return "minimax"; }

    @Override
    protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        return DeepSeekAdapter.buildOpenAiBody(
                req, "https://api.minimaxi.com/v1");
    }
}
