package com.lightai.spi.export;

import java.util.List;
import java.util.Objects;

/**
 * 导出批次（BE-054，4.6.3.6）：具有唯一 batchId，
 * 重试时保持相同 batchId 以支持接收端幂等。
 */
public record TraceExportBatch(
        String batchId,
        List<ExportedTrace> traces) {

    public TraceExportBatch {
        Objects.requireNonNull(batchId, "batchId 不能为空");
        traces = traces != null ? List.copyOf(traces) : List.of();
    }
}
