package com.lightai.admin.publish;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcConfigSnapshotPortAdapterTest {

    @Test
    void shouldReadEnabledSnapshotLimitsAndMergeCredentialDirectLimitByMinimum() {
        String aliasId = UUID.randomUUID().toString();
        String channelCredentialId = UUID.randomUUID().toString();
        Map<String, Object> content = Map.of(
                "limit_policies", List.of(
                        Map.of("scope_type", "MODEL_ALIAS", "scope_id", aliasId,
                                "rpm_limit", 10, "tpm_limit", 1000, "enabled", true),
                        Map.of("scope_type", "CREDENTIAL", "scope_id", channelCredentialId,
                                "rpm_limit", 20, "tpm_limit", 2000,
                                "concurrent_limit", 8, "enabled", true),
                        Map.of("scope_type", "PROVIDER_MODEL", "scope_id", UUID.randomUUID().toString(),
                                "rpm_limit", 1, "enabled", false)),
                "credentials", List.of(
                        Map.of("id", channelCredentialId, "rpm_limit", 15,
                                "tpm_limit", 3000, "concurrent_limit", 5)));

        JdbcConfigSnapshotPortAdapter adapter = new JdbcConfigSnapshotPortAdapter("light_ai", () -> null);
        var limits = adapter.parseCapacityLimits(content);

        assertThat(limits.get("MODEL_ALIAS:" + aliasId).rpmLimit()).isEqualTo(10L);
        assertThat(limits.get("CREDENTIAL:" + channelCredentialId).rpmLimit()).isEqualTo(15L);
        assertThat(limits.get("CREDENTIAL:" + channelCredentialId).tpmLimit()).isEqualTo(2000L);
        assertThat(limits.get("CREDENTIAL:" + channelCredentialId).concurrentLimit()).isEqualTo(5);
        assertThat(limits).hasSize(2);
    }

    @Test
    void shouldReadCircuitPolicyFromImmutableSnapshot() {
        String aliasId = UUID.randomUUID().toString();
        String policyId = UUID.randomUUID().toString();
        Map<String, Object> content = Map.of("reliability_policies", List.of(Map.of(
                "id", policyId, "alias_id", aliasId, "enabled", true,
                "circuit_window_seconds", 90, "circuit_min_requests", 5,
                "circuit_failure_rate", "0.25", "circuit_open_seconds", 40,
                "circuit_half_open_probes", 2, "circuit_half_open_successes", 1)));

        JdbcConfigSnapshotPortAdapter adapter = new JdbcConfigSnapshotPortAdapter("light_ai", () -> null);
        var policy = adapter.parseCircuitPolicies(content, 17).get(aliasId);

        assertThat(policy.policyId()).isEqualTo(UUID.fromString(policyId));
        assertThat(policy.snapshotNo()).isEqualTo(17);
        assertThat(policy.windowSeconds()).isEqualTo(90);
        assertThat(policy.failureRate()).isEqualTo(0.25);
        assertThat(policy.halfOpenProbes()).isEqualTo(2);
    }

    @Test
    void shouldReadEnabledQueuePolicyFromImmutableSnapshot() {
        String aliasId = UUID.randomUUID().toString();
        Map<String, Object> content = Map.of("limit_policies", List.of(
                Map.of("scope_type", "MODEL_ALIAS", "scope_id", aliasId,
                        "enabled", true, "overflow_strategy", "QUEUE",
                        "queue_timeout_ms", 2500, "queue_max_size", 40),
                Map.of("scope_type", "CREDENTIAL", "scope_id", UUID.randomUUID().toString(),
                        "enabled", false, "overflow", "QUEUE")));

        JdbcConfigSnapshotPortAdapter adapter = new JdbcConfigSnapshotPortAdapter("light_ai", () -> null);
        var policies = adapter.parseQueuePolicies(content);

        assertThat(policies).hasSize(1);
        assertThat(policies.get("MODEL_ALIAS:" + aliasId).queues()).isTrue();
        assertThat(policies.get("MODEL_ALIAS:" + aliasId).timeoutMs()).isEqualTo(2500);
        assertThat(policies.get("MODEL_ALIAS:" + aliasId).maxSize()).isEqualTo(40);
    }
}
