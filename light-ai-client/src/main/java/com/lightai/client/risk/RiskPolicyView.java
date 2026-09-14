package com.lightai.client.risk;

import java.math.BigDecimal;
import java.util.List;

public record RiskPolicyView(String id, long version, boolean enabled, String keywordAction, int anomalyWindowSeconds, Long anomalyRequestThreshold, Long anomalyTokenThreshold, BigDecimal anomalyAmountThreshold, int anomalyBlockSeconds, String whitelistMode, List<RiskKeywordRuleView> keywords, List<String> whitelistApplicationIds) {
    public RiskPolicyView { keywords = keywords == null ? List.of() : List.copyOf(keywords); whitelistApplicationIds = whitelistApplicationIds == null ? List.of() : List.copyOf(whitelistApplicationIds); }
}
