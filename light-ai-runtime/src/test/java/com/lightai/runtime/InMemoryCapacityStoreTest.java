package com.lightai.runtime.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 容量验收（BE-024）：三层原子预占、部分失败全不计数、原窗口结算、
 * 终态单次应用（重复释放幂等）、未发送请求退还 RPM。
 */
class InMemoryCapacityStoreTest {

    private InMemoryCapacityStore store;
    private final UUID aliasId = UUID.randomUUID();
    private final UUID modelId = UUID.randomUUID();
    private final UUID channelCredentialId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new InMemoryCapacityStore();
        store.registerLimit("alias", aliasId, new InMemoryCapacityStore.ScopeLimit(10L, 10000L, null));
        store.registerLimit("upstream_model", modelId,
                new InMemoryCapacityStore.ScopeLimit(10L, 10000L, null));
        store.registerLimit("channel_credential", channelCredentialId,
                new InMemoryCapacityStore.ScopeLimit(10L, 10000L, 5));
    }

    private CapacityStore.ReserveRequest request(long tokens) {
        return new CapacityStore.ReserveRequest(aliasId, modelId, channelCredentialId, tokens, 0);
    }

    @Test
    void reserveCountsAllThreeLayers() {
        var handle = store.reserve(request(100));
        assertThat(handle.reservedTokens()).isEqualTo(100);
        assertThat(store.usage("alias", aliasId).rpmReserved()).isEqualTo(1);
        assertThat(store.usage("upstream_model", modelId).tpmReserved()).isEqualTo(100);
        assertThat(store.usage("channel_credential", channelCredentialId).concurrentActive()).isEqualTo(1);
    }

    @Test
    void tpmReservationIncludesEffectiveMaxTokens() {
        var handle = store.reserve(new CapacityStore.ReserveRequest(
                aliasId, modelId, channelCredentialId, 100, 50));
        assertThat(handle.reservedTokens()).isEqualTo(150);
        assertThat(store.usage("alias", aliasId).tpmReserved()).isEqualTo(150);
    }

    @Test
    void partialFailureLeavesNoCounting() {
        // 凭证并发限制 5，连续 5 次成功；第 6 次在 credential 层失败：
        // alias/model 层已加的 RPM/TPM 必须回退
        for (int i = 0; i < 5; i++) {
            store.reserve(request(10));
        }
        long aliasRpmBefore = store.usage("alias", aliasId).rpmReserved();
        assertThatThrownBy(() -> store.reserve(request(10)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CAPACITY_LIMITED);
        assertThat(store.usage("alias", aliasId).rpmReserved()).isEqualTo(aliasRpmBefore);
        assertThat(store.usage("channel_credential", channelCredentialId).concurrentActive()).isEqualTo(5);
    }

    @Test
    void settleAdjustsTpmInOriginalWindowAndReleasesConcurrency() {
        var handle = store.reserve(request(100));
        store.settle(handle.reservationId(), 40, true);
        assertThat(store.usage("channel_credential", channelCredentialId).tpmReserved()).isEqualTo(40);
        assertThat(store.usage("channel_credential", channelCredentialId).concurrentActive()).isZero();
        // RPM 已发送：不退还
        assertThat(store.usage("alias", aliasId).rpmReserved()).isEqualTo(1);
    }

    @Test
    void settleWithoutSendingRefundsRpm() {
        var handle = store.reserve(request(100));
        store.settle(handle.reservationId(), 0, false);
        assertThat(store.usage("alias", aliasId).rpmReserved()).isZero();
    }

    @Test
    void releaseIsIdempotentAndRefundsFully() {
        var handle = store.reserve(request(100));
        store.release(handle.reservationId());
        assertThat(store.usage("alias", aliasId).rpmReserved()).isZero();
        assertThat(store.usage("alias", aliasId).tpmReserved()).isZero();
        assertThat(store.usage("channel_credential", channelCredentialId).concurrentActive()).isZero();
        // 重复释放幂等
        store.release(handle.reservationId());
        store.settle(handle.reservationId(), 10, true);
        assertThat(store.usage("alias", aliasId).rpmReserved()).isZero();
    }

    @Test
    void rpmExhaustionThrowsCapacityLimited() {
        // 结算且已发送：并发释放、RPM 保留，10 次后 RPM 耗尽
        for (int i = 0; i < 10; i++) {
            var handle = store.reserve(request(1));
            store.settle(handle.reservationId(), 1, true);
        }
        assertThatThrownBy(() -> store.reserve(request(1)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CAPACITY_LIMITED);
    }

    @Test
    void applicationAndKeyWindowsAreIndependentAndAtomic() {
        UUID applicationId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        var first = store.reserveApplication(applicationId, keyId, 40,
                new CapacityStore.ScopeLimit(2L, 100L, null),
                new CapacityStore.ScopeLimit(1L, 80L, null));
        assertThat(store.usage("application", applicationId).rpmReserved()).isEqualTo(1);
        assertThat(store.usage("application_key", keyId).tpmReserved()).isEqualTo(40);

        assertThatThrownBy(() -> store.reserveApplication(applicationId, keyId, 10,
                new CapacityStore.ScopeLimit(2L, 100L, null),
                new CapacityStore.ScopeLimit(1L, 80L, null)))
                .isInstanceOf(CapacityStore.CapacityLimitedException.class)
                .satisfies(error -> assertThat(
                        ((CapacityStore.CapacityLimitedException) error).scopeType())
                        .isEqualTo("application_key"));
        assertThat(store.usage("application", applicationId).rpmReserved()).isEqualTo(1);

        store.settle(first.reservationId(), 30, true);
        assertThat(store.usage("application", applicationId).tpmReserved()).isEqualTo(30);
        assertThat(store.usage("application_key", keyId).tpmReserved()).isEqualTo(30);
    }
}
