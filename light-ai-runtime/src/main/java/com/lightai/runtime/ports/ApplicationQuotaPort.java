package com.lightai.runtime.ports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 企业应用额度端口：一次业务请求只预占一次，所有终止路径以 reservationId 幂等结算或释放。
 * 旧访问凭证由 unlimited 实现跳过，V2 应用密钥必须接入持久化实现。
 */
public interface ApplicationQuotaPort {

    Reservation reserve(AccessTokenPort.Principal principal, String requestId,
                        long estimatedTokens, List<AmountEstimate> amountEstimates);

    void settle(Reservation reservation, Settlement settlement);

    void release(Reservation reservation, String reason);

    /** 回收实例异常退出后遗留的数据库预算预占。 */
    default int reclaimExpired(Instant now) {
        return 0;
    }

    record AmountEstimate(String currency, BigDecimal amount) {
        public AmountEstimate {
            amount = amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
        }
    }

    record Settlement(long inputTokens, long outputTokens, BigDecimal amount, String currency,
                      String usageSource, String virtualModelId, String providerModelId,
                      String inputPrice, String outputPrice, int priceUnit) {
        public Settlement {
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            amount = amount == null ? BigDecimal.ZERO : amount.max(BigDecimal.ZERO);
        }

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    record Reservation(String reservationId, String requestId, String applicationId,
                       String applicationKeyId, String capacityReservationId, boolean managed) {
    }

    static ApplicationQuotaPort unlimited() {
        return new ApplicationQuotaPort() {
            @Override
            public Reservation reserve(AccessTokenPort.Principal principal, String requestId,
                                       long estimatedTokens, List<AmountEstimate> amountEstimates) {
                return new Reservation(java.util.UUID.randomUUID().toString(), requestId,
                        principal == null ? null : principal.applicationId(),
                        principal == null ? null : principal.applicationKeyId(), null, false);
            }

            @Override
            public void settle(Reservation reservation, Settlement settlement) {
            }

            @Override
            public void release(Reservation reservation, String reason) {
            }
        };
    }
}
