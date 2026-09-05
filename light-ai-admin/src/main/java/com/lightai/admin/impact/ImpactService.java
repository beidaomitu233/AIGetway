package com.lightai.admin.impact;

import com.lightai.client.access.ImpactAnalysis;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.storage.access.ConfigReferenceQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 影响分析服务（BE-010/012/014/016 共用，4.2.9.5）。
 * impact_version 由当前引用关系摘要计算：引用变化必然产生不同值，
 * 停用/删除命令回传不一致即 IMPACT_ANALYSIS_EXPIRED，页面需重新确认。
 * can_delete=false 时 blockers 非空，删除命令直接 OBJECT_IN_USE。
 */
public class ImpactService {

    public static final String ENTITY_PROVIDER = "PROVIDER";
    public static final String ENTITY_POOL = "CREDENTIAL_POOL";
    public static final String ENTITY_CREDENTIAL = "CREDENTIAL";
    public static final String ENTITY_MODEL = "PROVIDER_MODEL";
    public static final String ENTITY_ALIAS = "MODEL_ALIAS";

    private final ConfigReferenceQuery referenceQuery;

    public ImpactService(ConfigReferenceQuery referenceQuery) {
        this.referenceQuery = referenceQuery;
    }

    public ImpactAnalysis analyze(Connection connection, String entityType, UUID entityId) {
        List<ImpactAnalysis.Reference> references = new ArrayList<>();
        List<String> affectedAliasIds = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        switch (entityType) {
            case ENTITY_PROVIDER -> {
                for (ConfigReferenceQuery.EntitySummary pool : referenceQuery.listPoolRefsOfProvider(connection, entityId)) {
                    references.add(new ImpactAnalysis.Reference(ENTITY_POOL, pool.id().toString(), pool.name(), "CREDENTIAL_POOL"));
                    blockers.add(ENTITY_POOL);
                }
                for (ConfigReferenceQuery.EntitySummary model : referenceQuery.listModelRefsOfProvider(connection, entityId)) {
                    references.add(new ImpactAnalysis.Reference(ENTITY_MODEL, model.id().toString(), model.name(), "PROVIDER_MODEL"));
                    blockers.add(ENTITY_MODEL);
                }
            }
            case ENTITY_POOL -> {
                int credentialCount = referenceQuery.countAliveCredentialsOfPool(connection, entityId);
                if (credentialCount > 0) {
                    references.add(new ImpactAnalysis.Reference(ENTITY_CREDENTIAL, entityId.toString(),
                            credentialCount + " credentials", ENTITY_CREDENTIAL));
                    blockers.add(ENTITY_CREDENTIAL);
                }
                for (ConfigReferenceQuery.EntitySummary candidate : referenceQuery.listCandidateRefsOfPool(connection, entityId)) {
                    references.add(new ImpactAnalysis.Reference("ROUTE_CANDIDATE", candidate.id().toString(),
                            candidate.name(), "ROUTE_CANDIDATE"));
                    blockers.add("ROUTE_CANDIDATE");
                }
                for (UUID aliasId : referenceQuery.listAliasIdsReferencingPool(connection, entityId)) {
                    affectedAliasIds.add(aliasId.toString());
                }
            }
            case ENTITY_MODEL -> {
                for (ConfigReferenceQuery.EntitySummary candidate : referenceQuery.listCandidateRefsOfModel(connection, entityId)) {
                    references.add(new ImpactAnalysis.Reference("ROUTE_CANDIDATE", candidate.id().toString(),
                            candidate.name(), "ROUTE_CANDIDATE"));
                    blockers.add("ROUTE_CANDIDATE");
                }
                for (UUID aliasId : referenceQuery.listAliasIdsReferencingModel(connection, entityId)) {
                    affectedAliasIds.add(aliasId.toString());
                }
            }
            case ENTITY_ALIAS -> {
                for (ConfigReferenceQuery.EntitySummary ref : referenceQuery.listAliasGovernanceRefs(connection, entityId)) {
                    references.add(new ImpactAnalysis.Reference(ref.relation(), ref.id().toString(), ref.name(), ref.relation()));
                    blockers.add(ref.relation());
                }
                affectedAliasIds.add(entityId.toString());
            }
            case ENTITY_CREDENTIAL -> {
                // Credential 删除引用关系在池级；运行占用由容量端口在服务层判定。
            }
            default -> throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "不支持的影响分析对象: " + entityType, "entity_type");
        }

        String impactVersion = digest(entityType, entityId, references);
        return new ImpactAnalysis(impactVersion, entityType, entityId.toString(),
                List.copyOf(references), List.copyOf(affectedAliasIds), blockers.isEmpty(), List.copyOf(blockers));
    }

    /** 校验确认票据：引用关系已变化时返回 IMPACT_ANALYSIS_EXPIRED。 */
    public void assertConfirmed(Connection connection, String entityType, UUID entityId, String confirmedImpactVersion) {
        if (confirmedImpactVersion == null || confirmedImpactVersion.isBlank()) {
            throw new LightAiException(ErrorCode.IMPACT_ANALYSIS_EXPIRED,
                    "缺少影响确认值，请重新获取影响分析", "confirmed_impact_version");
        }
        String current = analyze(connection, entityType, entityId).impactVersion();
        if (!constantTimeEquals(current, confirmedImpactVersion)) {
            throw new LightAiException(ErrorCode.IMPACT_ANALYSIS_EXPIRED,
                    "影响关系已变化，需要重新确认", "confirmed_impact_version");
        }
    }

    private static String digest(String entityType, UUID entityId, List<ImpactAnalysis.Reference> references) {
        StringBuilder material = new StringBuilder(entityType).append('|').append(entityId);
        references.stream()
                .map(ref -> ref.entityType() + ':' + ref.id() + ':' + ref.relation())
                .sorted()
                .forEach(ref -> material.append('|').append(ref));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("iv-");
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("影响版本摘要失败", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
