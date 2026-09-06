package com.lightai.admin.trace;

import com.lightai.admin.export.CsvStreamWriter;
import com.lightai.admin.trace.TraceListQueryParser.TraceListQuery;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.trace.JdbcTraceRepository;
import com.lightai.storage.trace.ObservationRows.TraceRow;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Trace CSV 流式导出（BE-036；4.4.5 导出规则）。
 * 先按同筛选计数，超过 100000 行在发送响应头前返回 EXPORT_TOO_LARGE；
 * 输出逐行游标写出，不在内存或磁盘保存完整文件；执行超 60 秒中止生成；
 * 连接断开由容器关闭输出流，try-with-resources 取消数据库游标。
 */
public class TraceExportService {

    public static final long MAX_ROWS = 100000L;
    private static final long MAX_DURATION_MS = 60000L;
    private static final int FETCH_SIZE = 1000;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final List<String> HEADERS = List.of(
            "started_at", "trace_id", "source_mode", "access_credential_name", "application",
            "project", "tenant", "alias", "requested_stream", "final_provider_name",
            "final_provider_model_name", "status", "attempt_count", "retry_count",
            "credential_failover_count", "fallback_count", "queued_ms", "first_token_ms",
            "total_ms", "usage_source", "input_tokens", "output_tokens", "total_tokens",
            "total_cost", "currency", "error_code");

    private final DataSource dataSource;
    private final JdbcTraceRepository traceRepository;

    public TraceExportService(DataSource dataSource, JdbcTraceRepository traceRepository) {
        this.dataSource = dataSource;
        this.traceRepository = traceRepository;
    }

    public org.springframework.http.ResponseEntity<StreamingResponseBody> export(
            RequestContext context, Map<String, List<String>> params) {
        RequestPermissions.require(context, Permissions.TRACE_EXPORT);
        TraceListQuery query = TraceListQueryParser.parse(params);
        if (query.startAt() == null || query.endAt() == null) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "导出必须提供时间范围",
                    List.of(new FieldIssue("start_at", "REQUIRED", "导出必须提供时间范围")));
        }
        JdbcTraceRepository.TraceFilter filter = TraceService.toFilter(query,
                TraceService.scopeApplications(context));

        long total;
        try (Connection connection = dataSource.getConnection()) {
            total = traceRepository.count(connection, filter);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.OBSERVATION_DATA_UNAVAILABLE,
                    "导出计数当前无法读取");
        }
        if (total > MAX_ROWS) {
            throw new LightAiException(ErrorCode.EXPORT_TOO_LARGE,
                    "导出结果超过 " + MAX_ROWS + " 行上限，请缩小时间范围");
        }

        String filename = "traces-" + FILE_TIME.format(query.startAt().toInstant())
                + "-" + FILE_TIME.format(query.endAt().toInstant()) + ".csv";
        long deadline = System.nanoTime() + Duration.ofMillis(MAX_DURATION_MS).toNanos();
        StreamingResponseBody body = outputStream -> streamRows(outputStream, filter,
                query, deadline);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=" + filename)
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private void streamRows(OutputStream outputStream, JdbcTraceRepository.TraceFilter filter,
                            TraceListQuery query, long deadline) {
        try (Connection connection = dataSource.getConnection()) {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream,
                    StandardCharsets.UTF_8), 64 * 1024);
            CsvStreamWriter.writeBom(writer);
            CsvStreamWriter.writeRow(writer, HEADERS);
            traceRepository.forEachRow(connection, filter, query.page().sort(), FETCH_SIZE,
                    row -> {
                        if (System.nanoTime() > deadline) {
                            throw new ExportDeadlineExceeded();
                        }
                        writeRow(writer, row);
                    });
            writer.flush();
        } catch (ExportDeadlineExceeded ignored) {
            // 60 秒执行上限：停止生成，已写内容保留，不伪造完整文件
        } catch (IOException ignored) {
            // 客户端断开：连接关闭即取消数据库游标
        } catch (Exception ignored) {
            // 响应头已发出，无法回写错误码；中断输出避免半截文件被误用为完整导出
        }
    }

    private void writeRow(Writer writer, TraceRow row) {
        try {
            List<String> cells = new ArrayList<>(HEADERS.size());
            cells.add(raw(row.startedAt()));
            cells.add(CsvStreamWriter.text(row.traceId()));
            cells.add(CsvStreamWriter.text(row.sourceMode()));
            cells.add(CsvStreamWriter.text(row.accessCredentialName()));
            cells.add(CsvStreamWriter.text(row.application()));
            cells.add(CsvStreamWriter.text(row.project()));
            cells.add(CsvStreamWriter.text(row.tenant()));
            cells.add(CsvStreamWriter.text(row.alias()));
            cells.add(String.valueOf(row.requestedStream()));
            cells.add(CsvStreamWriter.text(row.finalProviderName()));
            cells.add(CsvStreamWriter.text(row.finalProviderModelName()));
            cells.add(CsvStreamWriter.text(row.status()));
            cells.add(String.valueOf(row.attemptCount()));
            cells.add(String.valueOf(row.retryCount()));
            cells.add(String.valueOf(row.credentialFailoverCount()));
            cells.add(String.valueOf(row.fallbackCount()));
            cells.add(String.valueOf(row.queuedMs()));
            cells.add(raw(row.firstTokenMs()));
            cells.add(raw(row.totalMs()));
            cells.add(CsvStreamWriter.text(row.usageSource()));
            cells.add(String.valueOf(row.inputTokens()));
            cells.add(String.valueOf(row.outputTokens()));
            cells.add(String.valueOf(row.totalTokens()));
            cells.add(raw(row.totalCost()));
            cells.add(CsvStreamWriter.text(row.currency()));
            cells.add(CsvStreamWriter.text(row.errorCode()));
            CsvStreamWriter.writeRow(writer, cells);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String raw(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class ExportDeadlineExceeded extends RuntimeException {
    }
}
