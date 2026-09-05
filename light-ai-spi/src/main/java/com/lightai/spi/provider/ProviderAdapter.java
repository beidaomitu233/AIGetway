package com.lightai.spi.provider;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * ProviderAdapter SPI（BACKEND_PLAN 4.7.2.1）：内置 OPENAI/ANTHROPIC/GEMINI/DEEPSEEK
 * 与 CUSTOM_SPI 使用同一接口。单例注册、线程安全；方法不得把可变调用状态
 * 保存在实例字段。每次方法只执行一次外部请求，Adapter 内无重试/恢复/费用/Trace。
 */
public interface ProviderAdapter {

    /** 进程内唯一，必须与 Provider.type 完全匹配。 */
    String providerType();

    AdapterCapabilities capabilities();

    /** 本地结构校验：对 base_url、代理、超时与 Adapter 专属静态规则生成字段问题。 */
    default List<String> validateConfig(ProviderConfigView config) {
        return List.of();
    }

    /** 输入 Token 估算；不得访问 Provider 网络，返回值 ≥0。 */
    long estimateTokens(ProviderChatRequest request);

    /** 执行一次非流式外部调用；不重试。 */
    ProviderChatResponse chat(ProviderCallContext context);

    /**
     * 执行一次流式外部调用：每个订阅对应一次外部 HTTP 调用且只允许一个 Subscriber；
     * 取消 Subscription 必须关闭外部响应体。
     */
    Flow.Publisher<ProviderStreamChunk> streamChat(ProviderCallContext context);

    /** 错误分类（4.7.2.5 基线）：纯函数，unified_code 必须存在于 4.7.3。 */
    ProviderErrorClassification classifyError(ProviderFailure failure);

    /** 可选：管理员显式导入时读取外部模型列表；capabilities.supportsModelList=false 时不被调用。 */
    default List<ProviderModelDescriptor> listModels(ProviderCallContext context) {
        throw new UnsupportedOperationException("MODEL_LIST_NOT_SUPPORTED");
    }
}
