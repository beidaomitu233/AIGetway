package com.lightai.storage.risk;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RiskPolicyRecord(UUID id, long version, boolean enabled, String keywordAction, int anomalyWindowSeconds, Long anomalyRequestThreshold, Long anomalyTokenThreshold, BigDecimal anomalyAmountThreshold, int anomalyBlockSeconds, String whitelistMode, OffsetDateTime createdAt, OffsetDateTime updatedAt, List<RiskKeywordRuleRecord> keywords, List<UUID> whitelistApplicationIds) {
    public RiskPolicyRecord { keywords = keywords == null ? List.of() : List.copyOf(keywords); whitelistApplicationIds = whitelistApplicationIds == null ? List.of() : List.copyOf(whitelistApplicationIds); }
}
