package com.lightai.server.metrics;

import com.lightai.server.logging.SecurityLogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LightAiMetricsAndLoggingTest {

    private SimpleMeterRegistry registry;
    private LightAiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LightAiMetrics(registry);
    }

    @Test
    void testForbiddenTagsThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                LightAiMetrics.validateTagCardinality(List.of(Tag.of("trace_id", "trace-12345"))));

        assertThrows(IllegalArgumentException.class, () ->
                LightAiMetrics.validateTagCardinality(List.of(Tag.of("attempt_id", "attempt-999"))));

        assertThrows(IllegalArgumentException.class, () ->
                LightAiMetrics.validateTagCardinality(List.of(Tag.of("credential_id", "cred-abc"))));

        assertThrows(IllegalArgumentException.class, () ->
                LightAiMetrics.validateTagCardinality(List.of(Tag.of("access_token", "token-xyz"))));
    }

    @Test
    void testRequestMetricsRecording() {
        metrics.recordRequest("gpt-4o", "SUCCESS", "NONE", Duration.ofMillis(120));
        metrics.recordRequest("gpt-4o", "FAILED", "CIRCUIT_OPEN", Duration.ofMillis(50));

        double successCount = registry.get("light_ai.requests.total")
                .tag("model_alias", "gpt-4o")
                .tag("status", "SUCCESS")
                .counter()
                .count();
        assertEquals(1.0, successCount);

        double failedCount = registry.get("light_ai.requests.total")
                .tag("model_alias", "gpt-4o")
                .tag("status", "FAILED")
                .counter()
                .count();
        assertEquals(1.0, failedCount);
    }

    @Test
    void testTtftAndTokensRecording() {
        metrics.recordTtft("claude-3-5-sonnet", Duration.ofMillis(350));
        metrics.recordTokens("claude-3-5-sonnet", 100, 250);

        double promptTokens = registry.get("light_ai.tokens.total")
                .tag("model_alias", "claude-3-5-sonnet")
                .tag("token_type", "prompt")
                .counter()
                .count();
        assertEquals(100.0, promptTokens);

        double completionTokens = registry.get("light_ai.tokens.total")
                .tag("model_alias", "claude-3-5-sonnet")
                .tag("token_type", "completion")
                .counter()
                .count();
        assertEquals(250.0, completionTokens);
    }

    @Test
    void testCostSeparatedByCurrencyNoCrossSum() {
        metrics.recordCost("USD", 0.05);
        metrics.recordCost("CNY", 0.35);

        double usdCost = registry.get("light_ai.cost.total")
                .tag("currency", "USD")
                .counter()
                .count();
        assertEquals(0.05, usdCost, 0.0001);

        double cnyCost = registry.get("light_ai.cost.total")
                .tag("currency", "CNY")
                .counter()
                .count();
        assertEquals(0.35, cnyCost, 0.0001);
    }

    @Test
    void testFailoverAndCircuitBreakerAndCapacity() {
        metrics.recordFailover("CREDENTIAL_FAILOVER");
        metrics.recordFailover("FALLBACK");

        assertEquals(1.0, registry.get("light_ai.failover.total").tag("type", "CREDENTIAL_FAILOVER").counter().count());
        assertEquals(1.0, registry.get("light_ai.failover.total").tag("type", "FALLBACK").counter().count());

        metrics.setCircuitBreakerState("gpt-4o", "gpt-4o-001", 2); // 2 = OPEN
        assertEquals(2.0, registry.get("light_ai.circuit.breaker.state")
                .tag("model_alias", "gpt-4o")
                .gauge().value());

        metrics.setCapacityUtilization("concurrent", 0.75);
        assertEquals(0.75, registry.get("light_ai.capacity.utilization")
                .tag("dimension", "concurrent")
                .gauge().value());

        metrics.recordExporterFailure("trace");
        assertEquals(1.0, registry.get("light_ai.exporter.failures.total")
                .tag("exporter_type", "trace")
                .counter().count());
    }

    @Test
    void testSecurityLogSanitizerMasksSensitiveData() {
        String logLine = "Request with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 and sk-1234567890abcdef123456";
        assertTrue(SecurityLogSanitizer.containsRawSecret(logLine));

        String sanitized = SecurityLogSanitizer.sanitize(logLine);
        assertFalse(sanitized.contains("eyJhbGci"));
        assertFalse(sanitized.contains("1234567890abcdef"));
        assertTrue(sanitized.contains("Bearer [MASKED]"));
        assertTrue(sanitized.contains("sk-[MASKED]"));
        assertFalse(SecurityLogSanitizer.containsRawSecret(sanitized));

        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"my secret prompt content\"}]}";
        String sanitizedBody = SecurityLogSanitizer.sanitize(body);
        assertFalse(sanitizedBody.contains("my secret prompt content"));
        assertTrue(sanitizedBody.contains("[BODY_MASKED]"));
    }

    @Test
    void testSecurityLogSanitizerHeadersMasking() {
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer my-secret-token",
                "Cookie", "SESSIONID=secret12345",
                "Content-Type", "application/json",
                "X-Trace-Id", "trace-safe-id"
        );

        Map<String, String> sanitizedHeaders = SecurityLogSanitizer.sanitizeHeaders(headers);
        assertEquals("[MASKED]", sanitizedHeaders.get("Authorization"));
        assertEquals("[MASKED]", sanitizedHeaders.get("Cookie"));
        assertEquals("application/json", sanitizedHeaders.get("Content-Type"));
        assertEquals("trace-safe-id", sanitizedHeaders.get("X-Trace-Id"));
    }
}
