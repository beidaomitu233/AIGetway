package com.lightai.admin.impact;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.impact.ImpactAnalysis;
import com.lightai.client.impact.ImpactReference;
import com.lightai.storage.reference.JdbcConfigReferenceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 引用影响分析（BE-010/BE-012）。
 * impact_version 由当前引用关系摘要哈希计算，不落库：
 * 确认时以相同算法重算并比对，引用变化即返回 IMPACT_ANALYSIS_EXPIRED，
 * 页面重新展示影响内容。
 */
public class ImpactService {

    public static final String OPERATION_DISABLE = "DISABLE";
    public static final String OPERATION_DELETE = "DELETE";

    private final JdbcConfigReferenceRepository referenceRepository;
    private final String schemaName;

    public ImpactService(JdbcConfigReferenceRepository referenceRepository, String schemaName) {
        this.referenceRepository = referenceRepository;
        this.schemaName = schemaName;
    }

    public ImpactService(JdbcConfigReferenceRepository referenceRepository) {
        this(referenceRepository, com.lightai.storage.schema.ExpectedSchema.SCHEMA_NAME);
    }

    public ImpactAnalysis analyzeProvider(Connection connection, UUID providerId, String providerName) {
        List<ImpactReference> references = new ArrayList<>();
        Map<UUID, String> pools = referenceRepository.poolNamesByProvider(connection, providerId);
        pools.forEach((id, name) -> references.add(
                new ImpactReference("credential_pool", id.toString(), name, "CHILD_CREDENTIAL_POOL")));
        Map<UUID, String> models = referenceRepository.providerModelNamesByProvider(connection, providerId);
        models.forEach((id, name) -> references.add(
                new ImpactReference("provider_model", id.toString(), name, "CHILD_PROVIDER_MODEL")));

        List<UUID> affectedAliases = referenceRepository.aliasIdsByProvider(connection, providerId);
        return build("provider", providerId, providerName, references, affectedAliases);
    }

    public ImpactAnalysis analyzePool(Connection connection, UUID poolId, String poolName) {
        List<ImpactReference> references = new ArrayList<>();
        Map<UUID, String> credentials = referenceRepository.credentialNamesByPool(connection, poolId);
        credentials.forEach((id, name) -> references.add(
                new ImpactReference("credential", id.toString(), name, "CHILD_CREDENTIAL")));
        Map<UUID, String> candidates = referenceRepository.candidateNamesByPool(connection, poolId);
        candidates.forEach((id, name) -> references.add(
                new ImpactReference("route_candidate", id.toString(), name, "ROUTE_REFERENCE")));

        List<UUID> affectedAliases = aliasIdsOfPool(connection, poolId);
        return build("credential_pool", poolId, poolName, references, affectedAliases);
    }

    /** 停用/删除前的引用摘要比对；不一致抛 IMPACT_ANALYSIS_EXPIRED。 */
    public void verifyConfirmedImpact(String confirmedImpactVersion, ImpactAnalysis fresh) {
        if (!fresh.impactVersion().equals(confirmedImpactVersion)) {
            throw new LightAiException(ErrorCode.IMPACT_ANALYSIS_EXPIRED,
                    "引用关系已变化，请重新确认影响");
        }
    }

    private ImpactAnalysis build(String entityType, UUID entityId, String entityName,
                                 List<ImpactReference> references, List<UUID> affectedAliasIds) {
        List<ImpactReference> sorted = references.stream()
                .sorted(Comparator.comparing(ImpactReference::entityType)
                        .thenComparing(ImpactReference::id))
                .toList();
        boolean canDelete = sorted.isEmpty();
        List<String> blockers = canDelete ? List.of()
                : sorted.stream().map(reference -> reference.entityType() + ":" + reference.name()).toList();
        return new ImpactAnalysis(
                computeVersion(entityType, entityId, sorted, affectedAliasIds),
                entityType, entityId.toString(), sorted,
                affectedAliasIds.stream().map(UUID::toString).sorted().toList(),
                canDelete, blockers);
    }

    /** 引用关系摘要：内容寻址，稳定且随引用集合变化。 */
    public static String computeVersion(String entityType, UUID entityId,
                                 List<ImpactReference> references, List<UUID> aliasIds) {
        StringBuilder canonical = new StringBuilder(entityType).append('|').append(entityId);
        for (ImpactReference reference : references) {
            canonical.append('|').append(reference.entityType()).append(':')
                    .append(reference.id()).append(':').append(reference.name());
        }
        canonical.append("|aliases=");
        aliasIds.stream().map(UUID::toString).sorted().forEach(aliasId ->
                canonical.append(aliasId).append(','));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private List<UUID> aliasIdsOfPool(Connection connection, UUID poolId) {
        // 池关联的 Alias 集合：候选关系去重
        String sql = "SELECT DISTINCT alias_id FROM " + schemaQualifier()
                + ".route_candidate WHERE credential_pool_id = ? AND deleted_at IS NULL";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, poolId);
            try (var rs = statement.executeQuery()) {
                List<UUID> aliasIds = new ArrayList<>();
                while (rs.next()) {
                    aliasIds.add(rs.getObject(1, UUID.class));
                }
                return List.copyOf(aliasIds);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Alias引用查询失败", e);
        }
    }

    private String schemaQualifier() {
        return schemaName;
    }
}
