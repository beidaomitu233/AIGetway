package com.lightai.storage.draft;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * draft_change 查询端口（BE-037/BE-038）。
 * 列表、摘要、修订时间范围、按实体查找与删除；实现为活行（deleted_at IS NULL）语义。
 */
public interface DraftChangeQueryRepository {

    /** 差异筛选（draft-changes 列表）。集合条件命中任一值即视为选中；空集合表示不过滤。 */
    record DraftChangeFilter(
            String keyword,
            Set<String> entityTypes,
            Set<String> changeTypes,
            Set<String> modifiedBy,
            OffsetDateTime modifiedFrom,
            OffsetDateTime modifiedTo) {

        public DraftChangeFilter {
            entityTypes = entityTypes == null ? Set.of() : Set.copyOf(entityTypes);
            changeTypes = changeTypes == null ? Set.of() : Set.copyOf(changeTypes);
            modifiedBy = modifiedBy == null ? Set.of() : Set.copyOf(modifiedBy);
        }

        public boolean isEmpty() {
            return keyword == null && entityTypes.isEmpty() && changeTypes.isEmpty()
                    && modifiedBy.isEmpty() && modifiedFrom == null && modifiedTo == null;
        }
    }

    List<DraftChangeRow> list(Connection connection, DraftChangeFilter filter,
                              String sortExpression, int limit, long offset);

    long count(Connection connection, DraftChangeFilter filter);

    /** change_type → 数量摘要（草稿摘要页）。 */
    DraftChangeSummaryCounts summary(Connection connection);

    /** entity_type → 数量摘要（校验 change_summary 数据源）。 */
    java.util.Map<String, Long> countByEntityType(Connection connection);

    /** (entity_type, change_type) → 数量（校验 change_summary 矩阵数据源）。 */
    java.util.Map<String, java.util.Map<String, Long>> countByEntityTypeAndChangeType(Connection connection);

    /** 全部活行差异的最早/最近修改时间；无差异时为空。 */
    Optional<ModifiedRange> modifiedRange(Connection connection);

    Optional<DraftChangeRow> find(Connection connection, String entityType, UUID entityId);

    /** 删除单条差异（撤销 CREATE 抵消），返回删除行数。 */
    int delete(Connection connection, String entityType, UUID entityId);

    /** 清空全部差异（发布激活清空已发布草稿、撤销全部），返回删除行数。 */
    long deleteAll(Connection connection);

    record DraftChangeSummaryCounts(long total, long create, long update, long enable,
                                    long disable, long delete) {
    }

    record ModifiedRange(OffsetDateTime firstModifiedAt, OffsetDateTime lastModifiedAt) {
    }
}
