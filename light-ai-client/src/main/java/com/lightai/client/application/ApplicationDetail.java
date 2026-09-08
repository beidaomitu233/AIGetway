package com.lightai.client.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/** 应用工作台详情，聚合基本信息、额度速率和虚拟模型权限。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApplicationDetail(
        String id,
        String code,
        String name,
        String department,
        String ownerId,
        String ownerName,
        String environment,
        String description,
        String status,
        long activeKeyCount,
        ApplicationQuotaPolicyView quota,
        List<ApplicationModelPermissionView> models,
        OffsetDateTime lastCalledAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version) {

    public ApplicationDetail {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
