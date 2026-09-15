package com.lightai.admin.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.protocol.Roles;
import com.lightai.client.risk.RiskPolicyUpdateCommand;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.risk.JdbcRiskControlRepository;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class RiskControlServiceTest {
    private DataSource dataSource;
    private RiskControlService service;
    private UUID applicationId;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:risk_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        new DefaultSchemaMigrator(ds).migrate();
        dataSource = ds;
        var tx = new DataSourceTransactionManager(ds);
        tx.afterPropertiesSet();
        service = new RiskControlService(ds, new JdbcRiskControlRepository(), tx, null,
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));
        applicationId = UUID.randomUUID();
        try (var connection = ds.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO application (id,created_at,updated_at,version,code,name,owner_id,owner_name,environment,status) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, applicationId.toString());
            statement.setObject(2, "2026-09-15T00:00:00Z");
            statement.setObject(3, "2026-09-15T00:00:00Z");
            statement.setLong(4, 1);
            statement.setString(5, "risk-demo");
            statement.setString(6, "风险演示应用");
            statement.setString(7, "owner");
            statement.setString(8, "负责人");
            statement.setString(9, "TEST");
            statement.setString(10, "ACTIVE");
            statement.executeUpdate();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void listsApplicationCandidatesAndPersistsVersionedPolicy() {
        RequestContext admin = context(Roles.SYSTEM_ADMIN);
        assertThat(service.applications(admin)).extracting("id").contains(applicationId.toString());
        assertThat(service.policy(admin).version()).isEqualTo(1);

        var update = new RiskPolicyUpdateCommand(1, true, "BLOCK", 60, 20L, null, null, 120,
                "ENFORCE",
                List.of(new RiskPolicyUpdateCommand.RiskKeywordRuleCommand(
                        null, "secret", "CONTAINS", true, applicationId.toString(), "BLOCK", true)),
                List.of(applicationId.toString()), "enable policy");
        var saved = service.replace(admin, update);
        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.whitelistApplicationIds()).containsExactly(applicationId.toString());
        assertThat(service.policy(admin).keywords()).extracting("keyword").containsExactly("secret");
    }

    @Test
    void rejectsStaleVersionAndReadOnlyWrite() {
        RequestContext admin = context(Roles.SYSTEM_ADMIN);
        service.policy(admin);
        var update = new RiskPolicyUpdateCommand(1, true, "BLOCK", 60, null, null, null, 300,
                "OFF", List.of(), List.of(), "stale");
        service.replace(admin, update);
        assertThatThrownBy(() -> service.replace(admin, update))
                .isInstanceOfSatisfying(LightAiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.CONFIG_VERSION_CONFLICT));
        assertThatThrownBy(() -> service.replace(context(Roles.AUDITOR), update))
                .isInstanceOfSatisfying(LightAiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    private static RequestContext context(String role) {
        return new RequestContext(AuthContext.authenticated("admin", "管理员", Set.of(role), List.of()),
                "req-risk-service", "127.0.0.*");
    }
}

