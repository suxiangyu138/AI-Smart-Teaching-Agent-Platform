package com.chatbot.adapter;

import com.chatbot.model.UnifiedChatRequest;
import org.springframework.stereotype.Component;

/**
 * 智谱 GLM — OpenAI 兼容协议
 *
 * @author suxiangyu
 */
@Component
public class ZhipuGlmAdapter extends BaseModelAdapter {

    @Override
    public String getProviderCode() { return "zhipu"; }

    @Override
    protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        return DeepSeekAdapter.buildOpenAiBody(
                req, "https://open.bigmodel.cn/api/paas/v4");
    }
}
