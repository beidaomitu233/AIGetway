package com.lightai.admin.risk;

import com.lightai.client.chat.ChatMessage;
import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.RiskControlPort;
import com.lightai.runtime.ports.RiskWindowStore;
import com.lightai.storage.risk.JdbcRiskControlRepository;
import com.lightai.storage.risk.RiskEventRecord;
import com.lightai.storage.risk.RiskKeywordRuleRecord;
import com.lightai.storage.risk.RiskPolicyRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** 风险准入实现；异常消耗窗口通过 RiskWindowStore 支持单实例或 Redis 原子计数。 */
public final class JdbcRiskControlPort implements RiskControlPort {
    private final DataSource dataSource;
    private final JdbcRiskControlRepository repository;
    private final Clock clock;
    private final RiskWindowStore windowStore;

    public JdbcRiskControlPort(DataSource dataSource, JdbcRiskControlRepository repository, Clock clock) {
        this(dataSource, repository, clock, new com.lightai.runtime.ports.InMemoryRiskWindowStore());
    }

    public JdbcRiskControlPort(DataSource dataSource, JdbcRiskControlRepository repository, Clock clock,
                               RiskWindowStore windowStore) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.clock = clock;
        this.windowStore = windowStore == null ? new com.lightai.runtime.ports.InMemoryRiskWindowStore() : windowStore;
    }

    @Override
    public void check(AccessTokenPort.Principal principal, String requestId, UnifiedChatRequest request) {
        check(principal, requestId, request, 0L, null);
    }

    @Override
    public void check(AccessTokenPort.Principal principal, String requestId, UnifiedChatRequest request,
                      long estimatedTokens, BigDecimal estimatedAmount) {
        if (principal == null || principal.applicationId() == null) {
            return;
        }
        UUID applicationId;
        try {
            applicationId = UUID.fromString(principal.applicationId());
        } catch (Exception ignored) {
            return;
        }
        try (var connection = dataSource.getConnection()) {
            RiskPolicyRecord policy = repository.findPolicy(connection);
            if (policy == null || !policy.enabled()) {
                return;
            }
            if (!"OFF".equalsIgnoreCase(policy.whitelistMode())
                    && !policy.whitelistApplicationIds().contains(applicationId)) {
                boolean enforce = "ENFORCE".equalsIgnoreCase(policy.whitelistMode());
                event(connection, applicationId, requestId, "APPLICATION_NOT_WHITELISTED",
                        enforce ? "BLOCK" : "RECORD", null, "应用不在风险白名单");
                if (enforce) {
                    throw blocked("应用不在风险白名单");
                }
            }
            String content = request == null || request.messages() == null
                    ? ""
                    : request.messages().stream().map(ChatMessage::content).filter(Objects::nonNull)
                    .reduce("", (left, right) -> left + "\n" + right);
            for (RiskKeywordRuleRecord rule : policy.keywords()) {
                if (!rule.enabled() || rule.keyword() == null || rule.keyword().isBlank()
                        || (rule.applicationId() != null && !rule.applicationId().equals(applicationId))) {
                    continue;
                }
                String source = rule.ignoreCase() ? content.toLowerCase(java.util.Locale.ROOT) : content;
                String needle = rule.ignoreCase()
                        ? rule.keyword().toLowerCase(java.util.Locale.ROOT) : rule.keyword();
                boolean hit = "EXACT".equalsIgnoreCase(rule.matchType())
                        ? Arrays.stream(source.split("\\R", -1)).anyMatch(line -> line.trim().equals(needle))
                        : source.contains(needle);
                if (hit) {
                    event(connection, applicationId, requestId, "KEYWORD", rule.action(), rule.id(), "关键词规则命中");
                    if ("BLOCK".equalsIgnoreCase(rule.action())) {
                        throw blocked("请求命中关键词风险策略");
                    }
                }
            }
            if (policy.anomalyRequestThreshold() != null || policy.anomalyTokenThreshold() != null
                    || policy.anomalyAmountThreshold() != null) {
                long now = clock.millis();
                String key = applicationId + ":" + (now / Math.max(1, policy.anomalyWindowSeconds() * 1000L));
                if (windowStore.isBlocked(applicationId.toString(), now)) {
                    event(connection, applicationId, requestId, "ANOMALY_CONSUMPTION", "BLOCK", null,
                            "短时间消耗超过风险阈值");
                    throw blocked("短时间消耗超过风险阈值");
                }
                RiskWindowStore.Window window = windowStore.increment(key, policy.anomalyWindowSeconds(), 1,
                        Math.max(0L, estimatedTokens), estimatedAmount == null || estimatedAmount.signum() < 0
                                ? BigDecimal.ZERO : estimatedAmount);
                boolean exceeded = policy.anomalyRequestThreshold() != null
                        && window.requests() > policy.anomalyRequestThreshold()
                        || policy.anomalyTokenThreshold() != null && window.tokens() > policy.anomalyTokenThreshold()
                        || policy.anomalyAmountThreshold() != null
                        && window.amount().compareTo(policy.anomalyAmountThreshold()) > 0;
                if (exceeded) {
                    windowStore.block(applicationId.toString(), policy.anomalyBlockSeconds(), now);
                    event(connection, applicationId, requestId, "ANOMALY_CONSUMPTION", "BLOCK", null,
                            "短时间消耗超过风险阈值");
                    throw blocked("短时间消耗超过风险阈值");
                }
            }
        } catch (LightAiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LightAiException(ErrorCode.CONFIG_DATA_UNAVAILABLE, "风险策略不可用，已拒绝请求");
        }
    }

    private void event(java.sql.Connection connection, UUID applicationId, String requestId, String type,
                       String action, UUID ruleId, String reason) {
        repository.insertEvent(connection,
                new RiskEventRecord(UUID.randomUUID(), null, applicationId, requestId, type, action, ruleId, reason));
    }

    private static LightAiException blocked(String message) {
        return new LightAiException(ErrorCode.RISK_POLICY_BLOCKED, message);
    }
}
