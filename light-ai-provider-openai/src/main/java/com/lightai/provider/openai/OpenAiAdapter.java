package com.lightai.provider.openai;

import com.lightai.provider.common.ErrorClassificationBaseline;
import com.lightai.provider.common.OpenAiCompatibleAdapter;
import com.lightai.spi.provider.AdapterCapabilities;
import com.lightai.spi.provider.ProviderChatResponse;
import com.lightai.spi.provider.ProviderErrorClassification;
import com.lightai.spi.provider.ProviderFailure;
import java.util.List;
import java.util.Set;

/**
 * OPENAI Adapter（BE-026）：OpenAI Chat Completions 线协议。
 * 能力声明随运行版本固定；400 invalid_request_error 属于已校验参数被拒绝。
 */
public final class OpenAiAdapter extends OpenAiCompatibleAdapter {

    public static final String TYPE = "OPENAI";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/";

    private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(
            true, true, true, true,
            List.of("O200K", "CL100K"), 4,
            Set.of(ProviderChatResponse.FINISH_STOP, ProviderChatResponse.FINISH_LENGTH,
                    ProviderChatResponse.FINISH_CONTENT_FILTER),
            List.of());

    public OpenAiAdapter() {
        super(TYPE, DEFAULT_BASE_URL, CAPABILITIES);
    }

    @Override
    public ProviderErrorClassification classifyError(ProviderFailure failure) {
        if (failure.kind() == ProviderFailure.Kind.HTTP_STATUS
                && failure.httpStatus() != null && failure.httpStatus() == 400) {
            return ErrorClassificationBaseline.classify(failure, "PROVIDER_REQUEST_REJECTED");
        }
        return super.classifyError(failure);
    }
}
