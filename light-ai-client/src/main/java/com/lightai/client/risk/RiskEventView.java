package com.lightai.client.risk;

import java.time.OffsetDateTime;

public record RiskEventView(String id, OffsetDateTime createdAt, String applicationId, String requestId, String eventType, String action, String ruleId, String reason) {}
