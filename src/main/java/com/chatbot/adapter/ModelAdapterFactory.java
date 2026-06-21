package com.chatbot.adapter;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 适配器工厂 — 根据 provider 字段自动匹配
 *
 * @author suxiangyu
 */
@Component
public class ModelAdapterFactory {

    private final List<BaseModelAdapter> adapters;

    public ModelAdapterFactory(List<BaseModelAdapter> adapters) {
        this.adapters = adapters;
    }

    public BaseModelAdapter getAdapter(String providerCode) {
        return adapters.stream()
                .filter(a -> a.getProviderCode().equals(providerCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的模型厂商: " + providerCode));
    }

    public List<BaseModelAdapter> allAdapters() {
        return adapters;
    }
}
