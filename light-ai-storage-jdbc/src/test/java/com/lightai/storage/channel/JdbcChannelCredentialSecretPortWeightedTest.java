package com.lightai.storage.channel;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-223/BE-212 渠道 Key 选择顺序：同优先级内权重无放回排列，
 * 权重不跨优先级稀释主备关系，weight=0 不参与正常流量（全零兜底可用）。
 */
class JdbcChannelCredentialSecretPortWeightedTest {

    @Test
    void weightedOrderCoversAllKeysWithinSamePriority() {
        ChannelCredentialRecord first = record("k1", 1, 8);
        ChannelCredentialRecord second = record("k2", 1, 2);
        ChannelCredentialRecord third = record("k3", 1, 1);

        List<UUID> firstPicks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            List<ChannelCredentialRecord> order =
                    JdbcChannelCredentialSecretPort.weightedFailoverOrder(List.of(first, second, third));
            firstPicks.add(order.get(0).id());
            // failover 序列覆盖全部 Key 且不重复
            assertThat(order).extracting(ChannelCredentialRecord::id)
                    .doesNotHaveDuplicates()
                    .hasSize(3);
        }
        // 高权重 Key 在多次采样中获得更高首选中签率
        long firstKeyHeadShare = firstPicks.stream().filter(first.id()::equals).count();
        long thirdKeyHeadShare = firstPicks.stream().filter(third.id()::equals).count();
        assertThat(firstKeyHeadShare).isGreaterThan(thirdKeyHeadShare);
    }

    @Test
    void zeroWeightKeysExcludedUnlessGroupAllZero() {
        ChannelCredentialRecord active = record("active", 1, 5);
        ChannelCredentialRecord zero = record("zero", 1, 0);
        ChannelCredentialRecord disabled = record("down", 1, 0);

        List<ChannelCredentialRecord> order =
                JdbcChannelCredentialSecretPort.weightedFailoverOrder(List.of(active, zero, disabled));
        assertThat(order).hasSize(1);
        assertThat(order.get(0).name()).isEqualTo("active");

        // 全零兜底：仍可用且顺序确定，渠道不因权重配置失配而整体不可用
        ChannelCredentialRecord zeroA = record("zero-a", 1, 0);
        ChannelCredentialRecord zeroB = record("zero-b", 1, 0);
        List<ChannelCredentialRecord> fallback =
                JdbcChannelCredentialSecretPort.weightedFailoverOrder(List.of(zeroB, zeroA));
        List<ChannelCredentialRecord> fallbackAgain =
                JdbcChannelCredentialSecretPort.weightedFailoverOrder(List.of(zeroB, zeroA));
        assertThat(fallback).hasSize(2);
        assertThat(fallbackAgain).extracting(ChannelCredentialRecord::name)
                .isEqualTo(fallback.stream().map(ChannelCredentialRecord::name).toList());
    }

    @Test
    void higherPriorityGroupAlwaysPrecedesLowerPriority() {
        ChannelCredentialRecord backup = record("backup", 2, 100);
        ChannelCredentialRecord primary = record("primary", 1, 1);

        Set<String> observedFirstNames = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            List<ChannelCredentialRecord> order =
                    JdbcChannelCredentialSecretPort.weightedFailoverOrder(List.of(backup, primary));
            assertThat(order).extracting(ChannelCredentialRecord::name)
                    .containsExactly("primary", "backup");
            observedFirstNames.add(order.get(0).name());
        }
        assertThat(observedFirstNames).containsExactly("primary");
    }

    private static ChannelCredentialRecord record(String name, int priority, int weight) {
        return new ChannelCredentialRecord(UUID.nameUUIDFromBytes(name.getBytes()),
                UUID.randomUUID(), name, null, null, null, "sk-****" + name, 1,
                OffsetDateTime.now(), priority, weight, null, null, null,
                ChannelCredentialRecord.STATUS_ACTIVE, ChannelCredentialRecord.HEALTH_UNKNOWN,
                1, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
