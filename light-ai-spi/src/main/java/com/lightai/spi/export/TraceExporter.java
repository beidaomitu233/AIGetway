package com.lightai.spi.export;

import java.util.concurrent.CompletionStage;

/**
 * Trace 外部导出器 SPI（BE-054，4.6.3.6）：
 * 异步导出脱敏 Trace；失败重试由协调器统一管理。
 */
public interface TraceExporter {

    /**
     * 导出指定批次的 Trace 数据。
     *
     * @param batch 包含唯一 batchId 的批次
     * @return 异步结果
     */
    CompletionStage<TraceExportResult> export(TraceExportBatch batch);
}
