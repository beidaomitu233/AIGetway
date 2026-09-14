package com.lightai.storage.risk;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RiskEventRecord(UUID id, OffsetDateTime createdAt, UUID applicationId, String requestId, String eventType, String action, UUID ruleId, String reason) {}
