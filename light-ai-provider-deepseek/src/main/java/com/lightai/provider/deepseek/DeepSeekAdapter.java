package com.lightai.provider.deepseek;

import com.lightai.provider.common.OpenAiCompatibleAdapter;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import java.util.List;
import java.util.Set;

/**
 * DEEPSEEK Adapter（BE-026）：OpenAI 兼容线协议，独立 provider_type 与缺省地址。
 */
public final class DeepSeekAdapter extends OpenAiCompatibleAdapter {

    public static final String TYPE = "DEEPSEEK";
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1/";

    private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(
            true, true, true, true,
            List.of("DEEPSEEK"), 4,
            Set.of(ProviderChatResponse.FINISH_STOP, ProviderChatResponse.FINISH_LENGTH,
                    ProviderChatResponse.FINISH_CONTENT_FILTER),
            List.of());

    public DeepSeekAdapter() {
        super(TYPE, DEFAULT_BASE_URL, CAPABILITIES);
    }
}
