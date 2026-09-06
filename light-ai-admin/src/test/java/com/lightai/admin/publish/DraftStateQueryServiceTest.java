package com.lightai.admin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.lightai.admin.web.RequestContext;
import com.lightai.client.changes.FieldChange;
import com.lightai.client.paging.PageResult;
import com.lightai.client.publish.ConfigDraftState;
import com.lightai.client.publish.DraftChangeItem;
import com.lightai.client.publish.DraftChangeSummary;
import com.lightai.spi.auth.AuthContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BE-037 单元测试：草稿状态、差异摘要与差异列表展示映射。
 * 脱敏展示口径：changed=true 且无前后值 → sensitive=true（存储层已脱敏）。
 */
class DraftStateQueryServiceTest {

    private PublishTestSupport.RecordingConnection recording;
    private PublishTestSupport.FakeDraftStateRepository draftState;
    private PublishTestSupport.FakeDraftChangeQueryRepository changes;
    private PublishTestSupport.FakeDependencyRepository dependencies;

    @BeforeEach
    void setUp() {
        recording = new PublishTestSupport.RecordingConnection();
        draftState = new PublishTestSupport.FakeDraftStateRepository(recording.calls);
        changes = new PublishTestSupport.FakeDraftChangeQueryRepository(recording.calls);
        dependencies = new PublishTestSupport.FakeDependencyRepository(recording.calls);
    }

    private DraftStateQueryService service() {
        return new DraftStateQueryService(recording.dataSource(), draftState, changes, dependencies);
    }

    @Test
    void stateMapsSnapshotAndModifiedRange() {
        changes.add("provider", "OpenAI", "UPDATE", 3);

        ConfigDraftState state = service().state(context());

        assertThat(state.baseSnapshotNo()).isZero();
        assertThat(state.draftRevision()).isEqualTo(5);
        assertThat(state.changeCount()).isEqualTo(2);
        assertThat(state.status()).isEqualTo("EDITABLE");
        assertThat(state.firstModifiedAt()).isNotNull();
        assertThat(state.lastModifiedAt()).isNotNull();
    }

    @Test
    void stateHasNullRangeWhenNoChanges() {
        ConfigDraftState state = service().state(context());
        assertThat(state.firstModifiedAt()).isNull();
        assertThat(state.lastModifiedAt()).isNull();
    }

    @Test
    void summaryAggregatesByTypeAndEntity() {
        changes.add("provider", "OpenAI", "CREATE", 1);
        changes.add("provider", "OpenAI-2", "UPDATE", 2);
        changes.add("model_alias", "gpt-alias", "CREATE", 1);

        DraftChangeSummary summary = service().summary(context());

        assertThat(summary.totalCount()).isEqualTo(3);
        assertThat(summary.createCount()).isEqualTo(2);
        assertThat(summary.updateCount()).isEqualTo(1);
        assertThat(summary.byEntityType().get("provider")).isEqualTo(2);
        assertThat(summary.byEntityType().get("model_alias")).isEqualTo(1);
    }

    @Test
    void draftChangesMapsSensitiveFieldsAndBlockers() {
        dependencies.block("provider", "候选引用 OpenAI");
        changes.rows.add(new com.lightai.storage.draft.DraftChangeRow(
                UUID.randomUUID(), "provider", UUID.randomUUID(), "OpenAI",
                "UPDATE", List.of(
                FieldChange.sensitiveChanged("secret_ref"),
                FieldChange.changed("base_url", "https://a", "https://b")),
                "admin", 3, 5, OffsetDateTime.now(), OffsetDateTime.now()));

        PageResult<DraftChangeItem> page = service().draftChanges(context(), null,
                List.of(), List.of(), List.of(), 1, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.sort()).isEqualTo("updated_at desc");
        DraftChangeItem item = page.items().get(0);
        assertThat(item.entityType()).isEqualTo("provider");
        assertThat(item.entityVersion()).isEqualTo(3);
        assertThat(item.modifiedByName()).isEqualTo("admin");
        assertThat(item.changedFields()).anySatisfy(change -> {
            assertThat(change.sensitive()).isTrue();
            assertThat(change.beforeValue()).isNull();
            assertThat(change.afterValue()).isNull();
        });
        assertThat(item.changedFields()).anySatisfy(change -> {
            assertThat(change.sensitive()).isFalse();
            assertThat(change.afterValue()).isEqualTo("https://b");
        });
        // 有 CREATE 引用阻塞：不可单独撤销
        assertThat(item.revertable()).isFalse();
        assertThat(item.revertBlockers()).hasSize(1);
        assertThat(item.dependencySummary()).hasSize(1);
        assertThat(item.dependencySummary().get(0).entityName()).isEqualTo("候选引用 OpenAI");
    }

    @Test
    void draftChangesWithoutBlockersAreRevertable() {
        changes.add("model_alias", "gpt-alias", "CREATE", 1);
        PageResult<DraftChangeItem> page = service().draftChanges(context(), null,
                List.of(), List.of(), List.of(), 1, 20);
        assertThat(page.items().get(0).revertable()).isTrue();
        assertThat(page.items().get(0).revertBlockers()).isEmpty();
    }

    private static RequestContext context() {
        return new RequestContext(AuthContext.authenticated("user-admin", "管理员",
                Set.of("SYSTEM_ADMIN"), List.of()), "req-test", "203.0.113.*");
    }
}
