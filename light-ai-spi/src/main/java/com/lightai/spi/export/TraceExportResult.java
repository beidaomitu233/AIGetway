package com.lightai.spi.export;

import java.util.Objects;

/**
 * 导出执行结果（BE-054，4.6.3.6）。
 */
public record TraceExportResult(
        String batchId,
        boolean success,
        String errorMessage) {

    public TraceExportResult {
        Objects.requireNonNull(batchId, "batchId 不能为空");
    }

    public static TraceExportResult success(String batchId) {
        return new TraceExportResult(batchId, true, null);
    }

    public static TraceExportResult failure(String batchId, String errorMessage) {
        return new TraceExportResult(batchId, false, errorMessage);
    }
}
