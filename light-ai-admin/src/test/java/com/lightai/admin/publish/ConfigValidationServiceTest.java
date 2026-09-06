package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.provider.ProviderTypeRegistry;
import com.lightai.client.bootstrap.AdapterDeclaration;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.publish.ConfigValidateCommand;
import com.lightai.client.publish.ConfigValidationIssueView;
import com.lightai.client.publish.ConfigValidationResultView;
import com.lightai.spi.adapter.AdapterMetadataSource;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.publish.ConfigValidationRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * BE-039 单元测试：固定修订校验。校验不访问 Provider、不解析 Secret；
 * revision 变化失效、空草稿拒绝、校验凭据 10 分钟有效。
 */
class ConfigValidationServiceTest {

    private static final java.time.Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-06T02:00:00Z"), ZoneOffset.UTC);

    private PublishTestSupport.RecordingConnection recording;
    private PublishTestSupport.FakeDraftStateRepository draftState;
    private PublishTestSupport.FakeDraftChangeQueryRepository changes;
    private PublishTestSupport.FakeSnapshotRepository snapshots;
    private PublishTestSupport.FakeSnapshotContentRepository content;
    private PublishTestSupport.FakeValidationRepository validations;
    private PublishTestSupport.FakeRuntimeInstanceRepository instances;
    private PublishTestSupport.FakeDependencyRepository dependencies;
    private PublishTestSupport.RecordingAuditRepository audits;
    private ProviderTypeRegistry registry;
    private StubCheckRecordRepository checkRecords;
    private ConfigValidationService service;

    /** 检测记录桩：默认近期有成功检测（无 CONNECTION_CHECK_STALE 警告）。 */
    static final class StubCheckRecordRepository extends com.lightai.storage.check.JdbcProviderCheckRecordRepository {
        boolean recentSuccess = true;

        @Override
        public boolean existsSuccessSince(java.sql.Connection connection, String targetType,
                                          UUID targetId, OffsetDateTime since) {
            return recentSuccess;
        }
    }

    @BeforeEach
    void setUp() {
        recording = new PublishTestSupport.RecordingConnection();
        draftState = new PublishTestSupport.FakeDraftStateRepository(recording.calls);
        changes = new PublishTestSupport.FakeDraftChangeQueryRepository(recording.calls);
        snapshots = new PublishTestSupport.FakeSnapshotRepository(recording.calls);
        content = new PublishTestSupport.FakeSnapshotContentRepository(recording.calls);
        validations = new PublishTestSupport.FakeValidationRepository(recording.calls);
        instances = new PublishTestSupport.FakeRuntimeInstanceRepository(recording.calls);
        dependencies = new PublishTestSupport.FakeDependencyRepository(recording.calls);
        audits = new PublishTestSupport.RecordingAuditRepository(recording.calls);
        registry = new ProviderTypeRegistry((AdapterMetadataSource) () -> List.of(
                new AdapterDeclaration("OPENAI", "1.0.0-test", "https://api.openai.com/v1/",
                        List.of("O200K"), List.of("CHAT"), List.of())));
        checkRecords = new StubCheckRecordRepository();
        var transactionManager = new DataSourceTransactionManager(recording.dataSource());
        transactionManager.afterPropertiesSet();
        AuditService auditService = new AuditService(audits, recording.dataSource(),
                transactionManager, new PublishTestSupport.FailureCollector());
        service = new ConfigValidationService(recording.dataSource(), transactionManager,
                FIXED_CLOCK, draftState, changes, snapshots, content, validations, instances,
                registry, checkRecords, auditService, "Asia/Shanghai", "STANDALONE_SERVER");
        recording.onCommit = draftState::commit;
        recording.onRollback = draftState::rollback;
        changes.add("provider", "OpenAI", "UPDATE", 2);
    }

    @Test
    void validDraftPassesAndPersistsValidationWithTenMinuteExpiry() {
        content.content = validContent();

        ConfigValidationResultView view = service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(5));

        assertThat(view.status()).isEqualTo("PASSED");
        assertThat(view.issues()).isEmpty();
        assertThat(view.contentChecksum()).hasSize(64).matches("[0-9a-f]+");
        assertThat(view.draftRevision()).isEqualTo(5);
        assertThat(view.targetSnapshotNo()).isEqualTo(1);
        assertThat(Duration.between(view.validatedAt(), view.expiresAt()))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(view.changeSummary()).contains("provider");
        assertThat(validations.lastRecord.status()).isEqualTo(ConfigValidationRecord.STATUS_PASSED);
        assertThat(audits.inserted).hasSize(1);
        assertThat(recording.calls).endsWith("insert-validation", "audit-insert", "commit");
    }

    @Test
    void enabledAliasWithoutCandidateFailsValidation() {
        Map<String, Object> invalid = validContent();
        invalid.put("model_aliases", List.of(enabledAlias()));
        invalid.put("route_candidates", List.of());
        content.content = invalid;

        ConfigValidationResultView view = service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(5));

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("ALIAS_NO_AVAILABLE_CANDIDATE");
                    assertThat(issue.severity()).isEqualTo(ConfigValidationIssueView.SEVERITY_ERROR);
                    assertThat(issue.entityType()).isEqualTo("model_alias");
                    assertThat(issue.suggestion()).isNotBlank();
                });
        assertThat(view.issues()).allSatisfy(issue ->
                assertThat(issue.message()).doesNotContain("secret"));
    }

    @Test
    void candidateAcrossProvidersIsRejected() {
        Map<String, Object> invalid = validContent();
        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = (Map<String, Object>)
                ((List<?>) invalid.get("route_candidates")).get(0);
        candidate.put("credential_pool_id", "pool-other");
        invalid.put("credential_pools", List.of(enabledPool("pool-1", "p-1"),
                enabledPool("pool-other", "p-other")));
        invalid.put("providers", List.of(enabledProvider("p-1"), enabledProvider("p-other")));
        content.content = invalid;

        ConfigValidationResultView view = service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(5));

        assertThat(view.issues())
                .anySatisfy(issue -> assertThat(issue.code())
                        .isEqualTo("PROVIDER_RELATION_INVALID"));
    }

    @Test
    void staleConnectionChecksBecomeWarningsAndStillPass() {
        content.content = validContent();
        checkRecords.recentSuccess = false;

        ConfigValidationResultView view = service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(5));

        assertThat(view.status()).isEqualTo("PASSED");
        assertThat(view.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("CONNECTION_CHECK_STALE");
                    assertThat(issue.severity()).isEqualTo(ConfigValidationIssueView.SEVERITY_WARNING);
                });
        assertThat(validations.lastRecord.warningCount()).isPositive();
        assertThat(validations.lastRecord.errorCount()).isZero();
    }

    @Test
    void unregisteredAdapterTypeBlocksValidation() {
        Map<String, Object> invalid = validContent();
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>)
                ((List<?>) invalid.get("providers")).get(0);
        provider.put("type", "UNKNOWN_AI");
        content.content = invalid;

        ConfigValidationResultView view = service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(5));

        assertThat(view.issues())
                .anySatisfy(issue -> assertThat(issue.code()).isEqualTo("ADAPTER_UNAVAILABLE"));
    }

    @Test
    void revisionMismatchIsRejectedAsDraftChanged() {
        assertThatThrownBy(() -> service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(4)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.CONFIG_DRAFT_CHANGED);
        assertThat(recording.calls).contains("rollback");
        assertThat(audits.inserted).hasSize(1);
        assertThat(audits.inserted.get(0).result()).isEqualTo(AuditRecord.RESULT_FAILED);
        assertThat(audits.inserted.get(0).errorCode()).isEqualTo("CONFIG_DRAFT_CHANGED");
    }

    @Test
    void emptyDraftIsRejected() {
        changes.rows.clear();
        draftState.changeCount = 0;
        assertThatThrownBy(() -> service.validate("req-1", "admin", "203.0.113.*",
                new ConfigValidateCommand(5)))
                .isInstanceOf(LightAiException.class)
                .extracting(e -> ((LightAiException) e).code())
                .isEqualTo(ErrorCode.FIELD_VALIDATION_FAILED);
        assertThat(recording.calls).contains("rollback");
        assertThat(audits.inserted).hasSize(1);
        assertThat(audits.inserted.get(0).errorCode()).isEqualTo("FIELD_VALIDATION_FAILED");
    }

    // ---------- 内容夹具 ----------

    static Map<String, Object> validContent() {
        Map<String, Object> model = new java.util.LinkedHashMap<>();
        model.put("id", "m-1");
        model.put("provider_id", "p-1");
        model.put("display_name", "GPT Test");
        model.put("tokenizer_family", "O200K");
        model.put("context_window", 8000);
        model.put("max_output_tokens", 2000);
        model.put("input_price", new java.math.BigDecimal("0.0001"));
        model.put("output_price", new java.math.BigDecimal("0.0002"));
        model.put("price_unit", "1K_TOKENS");
        model.put("currency", "USD");
        model.put("enabled", true);
        Map<String, Object> alias = enabledAlias();
        Map<String, Object> candidate = new java.util.LinkedHashMap<>();
        candidate.put("id", "c-1");
        candidate.put("alias_id", "a-1");
        candidate.put("provider_model_id", "m-1");
        candidate.put("credential_pool_id", "pool-1");
        candidate.put("enabled", true);

        Map<String, Object> tree = new java.util.LinkedHashMap<>();
        tree.put("schema_version", 1);
        tree.put("providers", List.of(enabledProvider("p-1")));
        tree.put("credential_pools", List.of(enabledPool("pool-1", "p-1")));
        tree.put("credentials", List.of(enabledCredential()));
        tree.put("provider_models", List.of(model));
        tree.put("model_aliases", List.of(alias));
        tree.put("route_candidates", List.of(candidate));
        tree.put("limit_policies", List.of());
        tree.put("reliability_policies", List.of());
        return tree;
    }

    static Map<String, Object> enabledProvider(String id) {
        Map<String, Object> provider = new java.util.LinkedHashMap<>();
        provider.put("id", id);
        provider.put("name", "Provider " + id);
        provider.put("type", "OPENAI");
        provider.put("base_url", "https://api.openai.com/v1/");
        provider.put("enabled", true);
        return provider;
    }

    static Map<String, Object> enabledPool(String id, String providerId) {
        Map<String, Object> pool = new java.util.LinkedHashMap<>();
        pool.put("id", id);
        pool.put("provider_id", providerId);
        pool.put("name", "Pool " + id);
        pool.put("enabled", true);
        return pool;
    }

    static Map<String, Object> enabledCredential() {
        Map<String, Object> credential = new java.util.LinkedHashMap<>();
        credential.put("id", "cred-1");
        credential.put("pool_id", "pool-1");
        credential.put("name", "sk-***");
        credential.put("enabled", true);
        return credential;
    }

    static Map<String, Object> enabledAlias() {
        Map<String, Object> alias = new java.util.LinkedHashMap<>();
        alias.put("id", "a-1");
        alias.put("alias", "gpt-test");
        alias.put("display_name", "GPT 测试");
        alias.put("enabled", true);
        return alias;
    }
}
