package com.lightai.admin.risk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.client.chat.UnifiedChatRequest;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.runtime.ports.AccessTokenPort;
import com.lightai.runtime.ports.InMemoryRiskWindowStore;
import com.lightai.storage.risk.JdbcRiskControlRepository;
import com.lightai.storage.risk.RiskKeywordRuleRecord;
import com.lightai.storage.risk.RiskPolicyRecord;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskControlPortTest {
    private DataSource dataSource;
    private JdbcRiskControlRepository repository;
    private UUID applicationId;
    private AccessTokenPort.Principal principal;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:risk_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        new DefaultSchemaMigrator(ds).migrate();
        dataSource = ds;
        repository = new JdbcRiskControlRepository();
        applicationId = UUID.randomUUID();
        principal = AccessTokenPort.Principal.enterprise("demo", List.of(), applicationId.toString(),
                UUID.randomUUID().toString(), null, null);
    }

    @Test
    void blocksKeywordBeforeProviderCallAndRecordsEvent() {
        UUID ruleId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            repository.replace(connection,
                    new RiskPolicyRecord(UUID.randomUUID(), 1, true, "BLOCK", 60, null, null, null, 300,
                            "OFF", null, null,
                            List.of(new RiskKeywordRuleRecord(ruleId, "secret", "CONTAINS", true,
                                    null, "BLOCK", true)), List.of()),
                    List.of(new RiskKeywordRuleRecord(ruleId, "secret", "CONTAINS", true, null, "BLOCK", true)),
                    List.of());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var port = new JdbcRiskControlPort(dataSource, repository,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), new InMemoryRiskWindowStore());
        UnifiedChatRequest request = UnifiedChatRequest.builder().model("demo").addUserMessage("contains SECRET").build();
        assertThatThrownBy(() -> port.check(principal, "req-risk-1", request))
                .isInstanceOfSatisfying(LightAiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo(ErrorCode.RISK_POLICY_BLOCKED));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT event_type, action FROM risk_event")) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.getString(1)).isEqualTo("KEYWORD");
            org.assertj.core.api.Assertions.assertThat(result.getString(2)).isEqualTo("BLOCK");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void blocksApplicationOutsideEnforcedWhitelist() throws Exception {
        try (var connection = dataSource.getConnection()) {
            repository.replace(connection,
                    new RiskPolicyRecord(UUID.randomUUID(), 1, true, "BLOCK", 60, null, null, null,
                            300, "ENFORCE", null, null, List.of(), List.of()), List.of(), List.of());
        }
        var port = new JdbcRiskControlPort(dataSource, repository,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), new InMemoryRiskWindowStore());
        UnifiedChatRequest request = UnifiedChatRequest.builder().model("demo").addUserMessage("ok").build();
        assertThatThrownBy(() -> port.check(principal, "req-risk-whitelist", request))
                .isInstanceOfSatisfying(LightAiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo(ErrorCode.RISK_POLICY_BLOCKED));
    }

    @Test
    void blocksWhenTokenOrAmountWindowExceedsThreshold() throws Exception {
        try (var connection = dataSource.getConnection()) {
            repository.replace(connection,
                    new RiskPolicyRecord(UUID.randomUUID(), 1, true, "BLOCK", 60, null, 5L,
                            new BigDecimal("1.00"), 300, "OFF", null, null, List.of(), List.of()),
                    List.of(), List.of());
        }
        var port = new JdbcRiskControlPort(dataSource, repository,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), new InMemoryRiskWindowStore());
        UnifiedChatRequest request = UnifiedChatRequest.builder().model("demo").addUserMessage("ok").build();
        port.check(principal, "req-risk-2", request, 4, new BigDecimal("0.50"));
        assertThatThrownBy(() -> port.check(principal, "req-risk-3", request, 2, new BigDecimal("0.60")))
                .isInstanceOfSatisfying(LightAiException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo(ErrorCode.RISK_POLICY_BLOCKED));
    }
}
