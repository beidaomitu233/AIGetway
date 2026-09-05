package com.lightai.storage.access;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨配置实体只读查询端口（BE-P03 影响分析、检测编排与组合查询）。
 * 仅依赖 provider/credential_pool/credential/provider_model/route_candidate/model_alias
 * 及治理表的物理结构（DATABASE_PLAN §1/§2/§3/§5/§6/§7/§8/§9/§36），
 * 独立命名避免与 Provider/Pool 仓储包重叠；活行过滤 deleted_at IS NULL。
 */
public interface ConfigReferenceQuery {

    /** Provider 活行摘要（检测调用需要 type 与 base_url）。 */
    Optional<ProviderSummary> findProviderSummary(Connection connection, UUID providerId);

    /** 凭证池所属 Provider 的摘要（同 Provider 校验链）。 */
    Optional<ProviderSummary> findProviderSummaryOfPool(Connection connection, UUID poolId);

    Optional<EntitySummary> findPool(Connection connection, UUID poolId);

    /** Provider 下的凭证池引用（id+name）。 */
    List<EntitySummary> listPoolRefsOfProvider(Connection connection, UUID providerId);

    /** Provider 下的模型引用（id+display_name）。 */
    List<EntitySummary> listModelRefsOfProvider(Connection connection, UUID providerId);

    /** 池下凭证引用（id+name）。 */
    List<EntitySummary> listCredentialRefsOfPool(Connection connection, UUID poolId);

    /** 引用候选（id+alias 展示名）。 */
    List<EntitySummary> listCandidateRefsOfModel(Connection connection, UUID modelId);

    List<EntitySummary> listCandidateRefsOfPool(Connection connection, UUID poolId);

    /** 引用 Alias 的治理与接入实体（relation：LIMIT_POLICY/RELIABILITY_POLICY/ACCESS_CREDENTIAL）。 */
    List<EntitySummary> listAliasGovernanceRefs(Connection connection, UUID aliasId);

    /** 引用指定模型的候选所属 Alias 集合（去重，供 affected_alias_ids）。 */
    List<UUID> listAliasIdsReferencingModel(Connection connection, UUID modelId);

    /** 引用指定池的候选所属 Alias 集合（去重）。 */
    List<UUID> listAliasIdsReferencingPool(Connection connection, UUID poolId);

    /** 池下活行凭证数量（池影响与状态派生）。 */
    int countAliveCredentialsOfPool(Connection connection, UUID poolId);

    /** 池下第一个可用凭证（检测未指定 credential_id 时选择）。 */
    Optional<UUID> findFirstAliveCredentialIdOfPool(Connection connection, UUID poolId);

    record EntitySummary(UUID id, String name, String relation) {
    }

    record ProviderSummary(UUID id, String name, String type, String baseUrl, boolean enabled) {
    }
}
