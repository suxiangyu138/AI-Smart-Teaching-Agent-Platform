package com.chatbot.adapter;

import com.chatbot.model.UnifiedChatRequest;
import org.springframework.stereotype.Component;

/**
 * 小米 MiMo — OpenAI 兼容协议
 *
 * @author suxiangyu
 */
@Component
public class MiMoAdapter extends BaseModelAdapter {

    @Override
    public String getProviderCode() { return "mimo"; }

    @Override
    protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        return DeepSeekAdapter.buildOpenAiBody(
                req, "https://api.xiaomimimo.com/v1");
    }
}
