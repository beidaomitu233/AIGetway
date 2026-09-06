package com.lightai.client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * SDK 公开模型信息（BE-049，4.6.2.2）。
 */
public record ModelInfo(
        String id,
        String displayName,
        boolean supportStream,
        boolean supportSystem,
        Long contextWindow,
        Long maxOutputTokens,
        BigDecimal temperatureMin,
        BigDecimal temperatureMax,
        BigDecimal topPMin,
        BigDecimal topPMax,
        Integer stopMax,
        OffsetDateTime updatedAt) {
}