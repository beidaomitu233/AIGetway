package com.lightai.admin.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.provider.ProviderCheckCommand;
import com.lightai.client.provider.ProviderCheckRecord;
import com.lightai.client.provider.UsageSummary;
import com.lightai.spi.auth.AuthContext;
import com.lightai.spi.check.ProviderCheckExecutor;
import com.lightai.storage.check.CheckRecordRow;
import com.lightai.storage.check.JdbcProviderCheckRecordRepository;
import com.lightai.storage.provider.JdbcProviderRepository;
import com.lightai.storage.provider.ProviderRecord;
import com.lightai.storage.reference.JdbcConfigReferenceRepository;
import com.lightai.storage.runtime.JdbcRuntimeStateWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Provider 检测编排验收（BE-009）：命令目标校验、无 Adapter 时不伪造记录、
 * 真实执行结果落检测记录并收敛运行状态；检测不改配置 version。
 */
class ProviderCheckServiceTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();

    private static DataSource stubDataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                ProviderCheckServiceTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> null);
        return (DataSource) Proxy.newProxyInstance(
                ProviderCheckServiceTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) ->
                        "getConnection".equals(method.getName()) ? connection : null);
    }

    /** 只覆写所需读取路径的仓储桩：SQL 不执行。 */
    static class StubProviderRepository extends JdbcProviderRepository {
        ProviderRecord provider = new ProviderRecord(PROVIDER_ID, "OpenAI", "OPENAI",
                "https://api.openai.com/v1", null, 3000, 120000, Map.of(), true, 1L,
                OffsetDateTime.now(), OffsetDateTime.now());
        boolean missing;

        @Override
        public Optional<ProviderRecord> findLiveById(Connection connection, UUID id) {
            return missing ? Optional.empty() : Optional.of(provider);
        }
    }

    static class StubCheckRecordRepository extends JdbcProviderCheckRecordRepository {
        final List<CheckRecordRow> inserted = new java.util.ArrayList<>();

        @Override
        public void insert(Connection connection, CheckRecordRow row) {
            inserted.add(row);
        }
    }

    static class StubRuntimeStateWriter extends JdbcRuntimeStateWriter {
        final List<String> statuses = new java.util.ArrayList<>();

        @Override
        public void upsertProviderState(Connection connection, UUID providerId,
                                        String connectionStatus, OffsetDateTime checkedAt,
                                        String errorCode, String errorSummary) {
            statuses.add(connectionStatus);
        }
    }

    private static RequestContext adminContext() {
        return new RequestContext(
                AuthContext.authenticated("op-1", "管理员", java.util.Set.of("SYSTEM_ADMIN"),
                        List.of()), "req-check-1", "127.0.0.*");
    }

    @Test
    void missingModelTargetIsFieldValidationFailure() {
        ProviderCheckService service = new ProviderCheckService(stubDataSource(),
                new StubProviderRepository(), new JdbcConfigReferenceRepository(),
                new StubCheckRecordRepository(), new StubRuntimeStateWriter(),
                List.of(), "EMBEDDED");

        assertThatThrownBy(() -> service.check(adminContext(), PROVIDER_ID.toString(),
                new ProviderCheckCommand(null, null, null, null, null)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    @Test
    void bothModelTargetsRejected() {
        ProviderCheckService service = new ProviderCheckService(stubDataSource(),
                new StubProviderRepository(), new JdbcConfigReferenceRepository(),
                new StubCheckRecordRepository(), new StubRuntimeStateWriter(),
                List.of(), "EMBEDDED");
        assertThatThrownBy(() -> service.check(adminContext(), PROVIDER_ID.toString(),
                new ProviderCheckCommand("gpt-4o", UUID.randomUUID().toString(), null,
                        null, null)))
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
    }

    @Test
    void missingAdapterDoesNotFabricateRecord() {
        StubCheckRecordRepository records = new StubCheckRecordRepository();
        ProviderCheckService service = new ProviderCheckService(stubDataSource(),
                new StubProviderRepository(), new JdbcConfigReferenceRepository() {
                    @Override
                    public Optional<UUID> findModelIdByProviderAndModelId(Connection c,
                                                                          UUID providerId,
                                                                          String externalModelId) {
                        return Optional.of(UUID.randomUUID());
                    }
                }, records, new StubRuntimeStateWriter(), List.of(), "EMBEDDED");

        assertThatThrownBy(() -> service.check(adminContext(), PROVIDER_ID.toString(),
                new ProviderCheckCommand("gpt-4o", null, null,
                        ProviderCheckCommand.MODE_CONNECTION_ONLY, 5000)))
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.PROVIDER_ADAPTER_NOT_FOUND);
        assertThat(records.inserted).isEmpty();
    }

    @Test
    void successfulCheckPersistsRecordAndConvergesRuntimeState() {
        StubProviderRepository providers = new StubProviderRepository();
        StubCheckRecordRepository records = new StubCheckRecordRepository();
        StubRuntimeStateWriter runtimeStates = new StubRuntimeStateWriter();
        AtomicInteger invocations = new AtomicInteger();
        ProviderCheckExecutor executor = new ProviderCheckExecutor() {
            @Override
            public boolean supports(String providerType) {
                return "OPENAI".equals(providerType);
            }

            @Override
            public CheckOutcome execute(CheckInvocation invocation) {
                invocations.incrementAndGet();
                return CheckOutcome.success(812, UsageSummary.actual(9, 21), "req-abc-123");
            }
        };
        ProviderCheckService service = new ProviderCheckService(stubDataSource(),
                providers, new JdbcConfigReferenceRepository() {
                    @Override
                    public Optional<UUID> findModelIdByProviderAndModelId(Connection c,
                                                                          UUID providerId,
                                                                          String externalModelId) {
                        return Optional.of(UUID.randomUUID());
                    }
                }, records, runtimeStates, List.of(executor), "EMBEDDED");

        ProviderCheckRecord record = service.check(adminContext(), PROVIDER_ID.toString(),
                new ProviderCheckCommand("gpt-4o", null, null,
                        ProviderCheckCommand.MODE_CONNECTION_ONLY, null));

        assertThat(invocations.get()).isEqualTo(1);
        assertThat(record.status()).isEqualTo("SUCCEEDED");
        assertThat(record.totalMs()).isEqualTo(812);
        assertThat(record.providerRequestId()).isEqualTo("req-abc-123");
        assertThat(records.inserted).hasSize(1);
        assertThat(runtimeStates.statuses).containsExactly("AVAILABLE");
    }

    @Test
    void adapterFailureIsRecordedNotThrown() {
        ProviderCheckExecutor failing = new ProviderCheckExecutor() {
            @Override
            public boolean supports(String providerType) {
                return true;
            }

            @Override
            public CheckOutcome execute(CheckInvocation invocation) {
                throw new IllegalStateException("connect refused to https://...");
            }
        };
        StubCheckRecordRepository records = new StubCheckRecordRepository();
        StubRuntimeStateWriter runtimeStates = new StubRuntimeStateWriter();
        ProviderCheckService service = new ProviderCheckService(stubDataSource(),
                new StubProviderRepository(), new JdbcConfigReferenceRepository() {
                    @Override
                    public Optional<UUID> findModelIdByProviderAndModelId(Connection c,
                                                                          UUID providerId,
                                                                          String externalModelId) {
                        return Optional.of(UUID.randomUUID());
                    }
                }, records, runtimeStates, List.of(failing), "EMBEDDED");

        ProviderCheckRecord record = service.check(adminContext(), PROVIDER_ID.toString(),
                new ProviderCheckCommand("gpt-4o", null, null,
                        ProviderCheckCommand.MODE_CONNECTION_ONLY, null));

        assertThat(record.status()).isEqualTo("FAILED");
        assertThat(record.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(record.errorSummary()).doesNotContain("https://");
        assertThat(runtimeStates.statuses).containsExactly("UNAVAILABLE");
    }

    @Test
    void unknownProviderIsObjectNotFound() {
        StubProviderRepository providers = new StubProviderRepository();
        providers.missing = true;
        ProviderCheckService service = new ProviderCheckService(stubDataSource(),
                providers, new JdbcConfigReferenceRepository(), new StubCheckRecordRepository(),
                new StubRuntimeStateWriter(), List.of(), "EMBEDDED");
        assertThatThrownBy(() -> service.check(adminContext(), UUID.randomUUID().toString(),
                new ProviderCheckCommand("gpt-4o", null, null, null, null)))
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.OBJECT_NOT_FOUND);
    }
}
