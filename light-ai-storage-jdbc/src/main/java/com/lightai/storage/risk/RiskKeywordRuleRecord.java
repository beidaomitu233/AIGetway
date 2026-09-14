package com.lightai.storage.risk;

import java.util.UUID;

public record RiskKeywordRuleRecord(UUID id, String keyword, String matchType, boolean ignoreCase, UUID applicationId, String action, boolean enabled) {}
