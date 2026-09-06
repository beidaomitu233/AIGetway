package com.lightai.admin.publish;

import com.lightai.admin.web.RequestContext;
import com.lightai.client.publish.ConfigDraftState;
import com.lightai.client.publish.DraftChangeItem;
import com.lightai.client.publish.DraftChangeSummary;
import com.lightai.client.publish.DraftDependencyRef;
import com.lightai.client.publish.FieldChangeView;
import com.lightai.storage.draft.DraftChangeQueryRepository;
import com.lightai.storage.draft.DraftChangeRow;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import com.lightai.storage.publish.DraftDependencyRepository;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * 草稿状态与差异查询（BE-037，4.5.1）。
 * 草稿差异由配置写服务在事务内增量维护；此处只读聚合，
 * 秘密字段前后值在存储层已脱敏，展示层仅标记 sensitive。
 */
public class DraftStateQueryService {

    private final DataSource dataSource;
    private final DraftStateRepository draftStateRepository;
    private final DraftChangeQueryRepository draftChangeQueryRepository;
    private final DraftDependencyRepository dependencyRepository;

    public DraftStateQueryService(DataSource dataSource, DraftStateRepository draftStateRepository,
                                  DraftChangeQueryRepository draftChangeQueryRepository,
                                  DraftDependencyRepository dependencyRepository) {
        this.dataSource = dataSource;
        this.draftStateRepository = draftStateRepository;
        this.draftChangeQueryRepository = draftChangeQueryRepository;
        this.dependencyRepository = dependencyRepository;
    }

    public ConfigDraftState state(RequestContext context) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Optional<DraftStateSnapshot> draft = draftStateRepository.find(connection);
        Optional<DraftChangeQueryRepository.ModifiedRange> range =
                draftChangeQueryRepository.modifiedRange(connection);
        return new ConfigDraftState(
                draft.map(DraftStateSnapshot::baseSnapshotNo).orElse(0L),
                draft.map(DraftStateSnapshot::draftRevision).orElse(0L),
                draft.map(DraftStateSnapshot::changeCount).orElse(0),
                draft.map(DraftStateSnapshot::status).orElse(DraftStatus.EDITABLE).name(),
                range.map(DraftChangeQueryRepository.ModifiedRange::firstModifiedAt).orElse(null),
                range.map(DraftChangeQueryRepository.ModifiedRange::lastModifiedAt).orElse(null));
    }

    public DraftChangeSummary summary(RequestContext context) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        DraftChangeQueryRepository.DraftChangeSummaryCounts counts =
                draftChangeQueryRepository.summary(connection);
        Map<String, Long> byEntityType =
                new LinkedHashMap<>(draftChangeQueryRepository.countByEntityType(connection));
        return new DraftChangeSummary(counts.total(), counts.create(), counts.update(),
                counts.enable(), counts.disable(), counts.delete(), byEntityType);
    }

    public com.lightai.client.paging.PageResult<DraftChangeItem> draftChanges(
            RequestContext context, String keyword, List<String> entityTypes,
            List<String> changeTypes, List<String> modifiedBy, int page, int pageSize) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        DraftChangeQueryRepository.DraftChangeFilter filter = new DraftChangeQueryRepository.DraftChangeFilter(
                blankToNull(keyword),
                entityTypes == null ? Set.of() : Set.copyOf(entityTypes),
                changeTypes == null ? Set.of() : Set.copyOf(changeTypes),
                modifiedBy == null ? Set.of() : Set.copyOf(modifiedBy),
                null, null);
        long total = draftChangeQueryRepository.count(connection, filter);
        List<DraftChangeItem> items = draftChangeQueryRepository.list(
                        connection, filter, "updated_at desc", pageSize, (long) (page - 1) * pageSize)
                .stream().map(row -> toItem(connection, row)).toList();
        return com.lightai.client.paging.PageResult.of(items, total, page, pageSize,
                "updated_at desc", java.time.OffsetDateTime.now(),
                java.time.OffsetDateTime.now());
    }

    private DraftChangeItem toItem(Connection connection, DraftChangeRow row) {
        List<DraftDependencyRepository.Blocker> blockers =
                dependencyRepository.findCreateBlockers(connection, row.entityType(), row.entityId());
        boolean revertable = blockers.isEmpty();
        return new DraftChangeItem(
                row.id().toString(),
                row.entityType(),
                row.entityId().toString(),
                row.entityName(),
                row.changeType(),
                row.changedFields().stream()
                        .map(change -> new FieldChangeView(
                                change.fieldPath(),
                                change.before() == null ? null : String.valueOf(change.before()),
                                change.after() == null ? null : String.valueOf(change.after()),
                                change.changed() && change.before() == null && change.after() == null))
                        .toList(),
                blockers.stream()
                        .map(blocker -> new DraftDependencyRef(blocker.entityType(),
                                blocker.entityId(), blocker.entityName()))
                        .toList(),
                revertable,
                blockers.stream().map(DraftDependencyRepository.Blocker::entityId).toList(),
                row.modifiedBy(),
                row.modifiedBy(),
                row.updatedAt(),
                row.entityVersion());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
