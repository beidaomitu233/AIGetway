package com.lightai.runtime.ports;

import java.time.Instant;
import java.util.UUID;

/**
 * 渠道 Key 运行健康回写端口（BE-224）：429 写入冷却与复位时间，
 * 上游认证失败使 Key 退出选择。只写健康维度，不覆盖人工配置状态；
 * 实现失败不得影响主调用链路。
 */
public interface CredentialHealthPort {

    /** 上游 429：Key 进入 RATE_LIMITED 冷却，resetAt 之前不参与选择。 */
    void markRateLimited(UUID channelCredentialId, Instant resetAt, String errorCode, String summary);

    /** 上游认证失败：Key 健康标记为 INVALID，退出选择，直到检测或人工恢复。 */
    void markAuthFailed(UUID channelCredentialId, String errorCode, String summary);

    /** 无共享运行状态时的空实现。 */
    static CredentialHealthPort noop() {
        return new CredentialHealthPort() {
            @Override public void markRateLimited(UUID channelCredentialId, Instant resetAt,
                                                  String errorCode, String summary) {
            }

            @Override public void markAuthFailed(UUID channelCredentialId, String errorCode, String summary) {
            }
        };
    }
}
