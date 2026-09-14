package com.lightai.client.risk;

public record RiskKeywordRuleView(String id, String keyword, String matchType, boolean ignoreCase, String applicationId, String action, boolean enabled) {}
