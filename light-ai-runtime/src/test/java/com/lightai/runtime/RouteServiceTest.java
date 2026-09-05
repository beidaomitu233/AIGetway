package com.lightai.runtime.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * 路由验收（BE-019）：能力/上下文/熔断过滤，同级权重无放回，
 * 过滤不创建 Attempt、不消耗预算；确定随机源可复算。
 */
class RouteServiceTest {

    private static RouteCandidateView candidate(int priority, int weight, Boolean stream,
                                                Boolean system, Long context) {
        return new RouteCandidateView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), priority, weight, true, stream, system, context,
                4096, false);
    }

    private static final RandomGenerator FIXED = new RandomGenerator() {
        @Override
        public double nextDouble() {
            return 0.0; // 恒取第一权重段：顺序确定
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    };

    @Test
    void streamRequirementFiltersNonStreamingCandidates() {
        RouteService service = new RouteService(FIXED);
        var decision = service.route(
                List.of(candidate(10, 1, false, true, 100000L),
                        candidate(10, 1, true, true, 100000L)),
                new CapabilityRequirement(true, true, 100, 1000));
        assertThat(decision.ordered()).hasSize(1);
        assertThat(decision.excluded()).hasSize(1);
        assertThat(decision.excluded().get(0).reason()).isEqualTo("STREAM_UNSUPPORTED");
    }

    @Test
    void noCapableCandidateThrowsCapabilityError() {
        RouteService service = new RouteService(FIXED);
        assertThatThrownBy(() -> service.route(
                List.of(candidate(10, 1, false, true, 100000L)),
                new CapabilityRequirement(true, true, 100, 1000)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.MODEL_CAPABILITY_NOT_SUPPORTED);
    }

    @Test
    void contextWindowExceededThrowsContextError() {
        RouteService service = new RouteService(FIXED);
        assertThatThrownBy(() -> service.route(
                List.of(candidate(10, 1, true, true, 500L)),
                new CapabilityRequirement(false, false, 400, 1000)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONTEXT_WINDOW_EXCEEDED);
    }

    @Test
    void circuitOpenAndDisabledExcludedWithoutBudget() {
        RouteService service = new RouteService(FIXED);
        var open = new RouteCandidateView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 10, 1, true, true, true, 100000L, 4096, true);
        var disabled = new RouteCandidateView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 10, 1, false, true, true, 100000L, 4096, false);
        var decision = service.route(List.of(open, disabled),
                new CapabilityRequirement(false, false, 100, 100));
        assertThat(decision.ordered()).isEmpty();
        assertThat(decision.excluded()).extracting(
                com.lightai.runtime.route.CapabilityRequirement.ExcludedCandidate::reason)
                .containsExactly("CIRCUIT_OPEN", "DISABLED");
    }

    @Test
    void priorityOrderingIsDeterministic() {
        RouteService service = new RouteService(FIXED);
        var low = candidate(20, 50, true, true, 100000L);
        var high = candidate(10, 1, true, true, 100000L);
        var decision = service.route(List.of(low, high),
                new CapabilityRequirement(false, false, 100, 100));
        assertThat(decision.ordered()).containsExactly(high, low);
    }
}
