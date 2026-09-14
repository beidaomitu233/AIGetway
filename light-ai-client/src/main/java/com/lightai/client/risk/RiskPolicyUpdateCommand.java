package com.lightai.client.risk;

import java.math.BigDecimal;
import java.util.List;

public record RiskPolicyUpdateCommand(long version, boolean enabled, String keywordAction, int anomalyWindowSeconds, Long anomalyRequestThreshold, Long anomalyTokenThreshold, BigDecimal anomalyAmountThreshold, int anomalyBlockSeconds, String whitelistMode, List<RiskKeywordRuleCommand> keywords, List<String> whitelistApplicationIds, String reason) {
    public record RiskKeywordRuleCommand(String id, String keyword, String matchType, Boolean ignoreCase, String applicationId, String action, Boolean enabled) {}
}
