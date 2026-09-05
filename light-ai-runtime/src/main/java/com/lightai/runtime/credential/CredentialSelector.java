package com.lightai.runtime.credential;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.pool.SelectionStrategy;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * 凭证选择器（BE-020）。
 * 过滤：禁用、INVALID/UNAVAILABLE/DISABLED 一律排除，RATE_LIMITED 未复位排除；
 * 排序：HEALTHY 优先于 UNKNOWN；三种选择策略（LEAST_CONCURRENT/ROUND_ROBIN/
 * WEIGHTED_RANDOM）。无可选凭证返回 CREDENTIAL_NOT_AVAILABLE。
 * 句柄规则：调用方用毕必须 clear，不缓存明文（P05 Resolver 落地）。
 */
public class CredentialSelector {

    /** 候选凭证视图（只读）。 */
    public record CredentialView(UUID id, UUID poolId, int weight, String healthStatus,
                                 Instant rateLimitResetAt, boolean enabled, long currentConcurrency) {
    }

    /** 选中的短期句柄：调用结束必须 clear。 */
    public record CredentialHandle(UUID credentialId) {
    }

    public interface HandleCleaner {
        void clear(CredentialHandle handle);
    }

    private final RandomGenerator random;
    private final AtomicLong roundRobinCursor = new AtomicLong();

    public CredentialSelector(RandomGenerator random) {
        this.random = random;
    }

    public CredentialHandle select(List<CredentialView> poolCredentials, SelectionStrategy strategy,
                                   Instant now) {
        if (strategy == null) {
            strategy = SelectionStrategy.LEAST_CONCURRENT;
        }
        List<CredentialView> selectable = poolCredentials.stream()
                .filter(credential -> credential.enabled())
                .filter(credential -> !"INVALID".equals(credential.healthStatus())
                        && !"UNAVAILABLE".equals(credential.healthStatus())
                        && !"DISABLED".equals(credential.healthStatus()))
                .filter(credential -> !"RATE_LIMITED".equals(credential.healthStatus())
                        || credential.rateLimitResetAt() == null
                        || !credential.rateLimitResetAt().isAfter(now))
                .toList();
        if (selectable.isEmpty()) {
            throw new LightAiException(ErrorCode.CREDENTIAL_NOT_AVAILABLE,
                    "当前凭证池没有可用 Credential");
        }
        // HEALTHY 优先于 UNKNOWN；同级按策略选择
        List<CredentialView> healthy = selectable.stream()
                .filter(credential -> "HEALTHY".equals(credential.healthStatus())).toList();
        List<CredentialView> candidates = healthy.isEmpty() ? selectable : healthy;
        CredentialView picked = switch (strategy) {
            case LEAST_CONCURRENT -> candidates.stream()
                    .min((left, right) -> Long.compare(left.currentConcurrency(),
                            right.currentConcurrency()))
                    .orElse(candidates.get(0));
            case ROUND_ROBIN -> candidates.get(
                    (int) (roundRobinCursor.getAndIncrement() % candidates.size()));
            case WEIGHTED_RANDOM -> weightedPick(candidates);
        };
        return new CredentialHandle(picked.id());
    }

    private CredentialView weightedPick(List<CredentialView> candidates) {
        int totalWeight = candidates.stream().mapToInt(CredentialView::weight).sum();
        double target = random.nextDouble() * totalWeight;
        double accumulated = 0;
        for (CredentialView candidate : candidates) {
            accumulated += candidate.weight();
            if (accumulated > target) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }
}
