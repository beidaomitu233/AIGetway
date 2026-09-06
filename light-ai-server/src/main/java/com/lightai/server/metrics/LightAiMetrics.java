package com.lightai.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轻享 AI 运行时低基数指标采集器（PRD 5.5，BE-057）：
 * 1. 采集请求量、错误率、耗时分位数、首 Token 耗时、Token、费用（分币种）、重试、Failover、Fallback、熔断与容量使用率。
 * 2. 严格低基数约束：严禁将 trace_id、attempt_id、credential_id 或 access_token 作为指标 Tag。
 */
@Component
public class LightAiMetrics {

    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "trace_id", "traceid", "x-trace-id",
            "attempt_id", "attemptid",
            "credential_id", "credentialid",
            "token", "access_token", "secret"
    );

    private final MeterRegistry registry;

    private final Map<String, AtomicInteger> circuitBreakerGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> capacityGauges = new ConcurrentHashMap<>();

    public LightAiMetrics(MeterRegistry registry) {
        this.registry = registry != null ? registry : new SimpleMeterRegistry();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    /**
     * 校验标签基数安全，防止动态高基数 ID 污染时序数据库
     */
    public static void validateTagCardinality(Iterable<Tag> tags) {
        if (tags == null) return;
        for (Tag tag : tags) {
            String key = tag.getKey().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_TAG_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "高基数标识严禁作为指标标签: " + tag.getKey() + " (违反 PRD 5.5 / BE-057 规范)");
            }
        }
    }

    public void recordRequest(String modelAlias, String status, String errorCode, Duration duration) {
        String safeAlias = modelAlias != null ? modelAlias : "unknown";
        String safeStatus = status != null ? status : "UNKNOWN";
        String safeErrorCode = errorCode != null ? errorCode : "NONE";

        List<Tag> tags = List.of(
                Tag.of("model_alias", safeAlias),
                Tag.of("status", safeStatus),
                Tag.of("error_code", safeErrorCode)
        );
        validateTagCardinality(tags);

        registry.counter("light_ai.requests.total", tags).increment();
        if (duration != null) {
            Timer.builder("light_ai.request.duration.seconds")
                    .tags(List.of(Tag.of("model_alias", safeAlias), Tag.of("status", safeStatus)))
                    .publishPercentiles(0.5, 0.9, 0.95)
                    .register(registry)
                    .record(duration);
        }
    }

    public void recordTtft(String modelAlias, Duration ttft) {
        String safeAlias = modelAlias != null ? modelAlias : "unknown";
        List<Tag> tags = List.of(Tag.of("model_alias", safeAlias));
        validateTagCardinality(tags);

        if (ttft != null) {
            Timer.builder("light_ai.ttft.seconds")
                    .tags(tags)
                    .publishPercentiles(0.5, 0.9, 0.95)
                    .register(registry)
                    .record(ttft);
        }
    }

    public void recordTokens(String modelAlias, long promptTokens, long completionTokens) {
        String safeAlias = modelAlias != null ? modelAlias : "unknown";
        List<Tag> promptTags = List.of(Tag.of("model_alias", safeAlias), Tag.of("token_type", "prompt"));
        List<Tag> completionTags = List.of(Tag.of("model_alias", safeAlias), Tag.of("token_type", "completion"));
        validateTagCardinality(promptTags);
        validateTagCardinality(completionTags);

        if (promptTokens > 0) {
            registry.counter("light_ai.tokens.total", promptTags).increment(promptTokens);
        }
        if (completionTokens > 0) {
            registry.counter("light_ai.tokens.total", completionTags).increment(completionTokens);
        }
    }

    /**
     * 分币种记录费用，严禁跨币种求和（PROJECT_DOCUMENT 架构原则）
     */
    public void recordCost(String currency, double cost) {
        String safeCurrency = currency != null ? currency.toUpperCase(Locale.ROOT) : "UNKNOWN";
        List<Tag> tags = List.of(Tag.of("currency", safeCurrency));
        validateTagCardinality(tags);

        if (cost > 0) {
            registry.counter("light_ai.cost.total", tags).increment(cost);
        }
    }

    public void recordFailover(String type) {
        String safeType = type != null ? type.toUpperCase(Locale.ROOT) : "UNKNOWN";
        List<Tag> tags = List.of(Tag.of("type", safeType));
        validateTagCardinality(tags);

        registry.counter("light_ai.failover.total", tags).increment();
    }

    public void setCircuitBreakerState(String modelAlias, String modelId, int state) {
        String safeAlias = modelAlias != null ? modelAlias : "unknown";
        String safeModelId = modelId != null ? modelId : "unknown";
        String key = safeAlias + ":" + safeModelId;

        circuitBreakerGauges.computeIfAbsent(key, k -> {
            AtomicInteger gaugeVal = new AtomicInteger(state);
            List<Tag> tags = List.of(Tag.of("model_alias", safeAlias), Tag.of("model_id", safeModelId));
            validateTagCardinality(tags);
            Gauge.builder("light_ai.circuit.breaker.state", gaugeVal, AtomicInteger::get)
                    .tags(tags)
                    .description("0=CLOSED, 1=HALF_OPEN, 2=OPEN")
                    .register(registry);
            return gaugeVal;
        }).set(state);
    }

    public void setCapacityUtilization(String dimension, double ratio) {
        String safeDim = dimension != null ? dimension : "default";
        capacityGauges.computeIfAbsent(safeDim, k -> {
            AtomicReference<Double> val = new AtomicReference<>(ratio);
            List<Tag> tags = List.of(Tag.of("dimension", safeDim));
            validateTagCardinality(tags);
            Gauge.builder("light_ai.capacity.utilization", val, AtomicReference::get)
                    .tags(tags)
                    .register(registry);
            return val;
        }).set(ratio);
    }

    public void recordExporterFailure(String exporterType) {
        String safeType = exporterType != null ? exporterType : "trace";
        List<Tag> tags = List.of(Tag.of("exporter_type", safeType));
        validateTagCardinality(tags);

        registry.counter("light_ai.exporter.failures.total", tags).increment();
    }
}
