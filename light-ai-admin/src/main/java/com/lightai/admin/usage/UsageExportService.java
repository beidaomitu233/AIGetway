package com.lightai.admin.usage;

import com.lightai.admin.export.CsvStreamWriter;
import com.lightai.admin.usage.UsageQueryParser.UsageQuery;
import com.lightai.admin.usage.UsageService.ResolvedQuery;
import com.lightai.admin.web.RequestContext;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.protocol.Permissions;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.AggregateFilter;
import com.lightai.storage.trace.JdbcUsageAggregateRepository.ExportRow;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Usage CSV 流式导出服务（BE-036；4.4.5 导出规则）。
 * 每行对应一个时间桶、分组值与币种组合；文件名
 * usage-{granularity}-{group_by}-{start_at}-{end_at}.csv；
 * 超过 100000 行在响应头前返回 EXPORT_TOO_LARGE；60 秒执行上限与断开取消游标。
 */
public class UsageExportService {

    public static final long MAX_ROWS = 100000L;
    private static final long MAX_DURATION_MS = 60000L;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final List<String> HEADERS = List.of(
            "bucket_start", "bucket_end", "dimension_type", "dimension_id", "dimension_name",
            "request_count", "success_count", "failure_count", "success_rate", "attempt_count",
            "initial_count", "retry_count", "credential_failover_count", "fallback_count",
            "half_open_probe_count", "actual_tokens", "estimated_tokens", "total_tokens",
            "input_cost", "output_cost", "total_cost", "currency");

    private final DataSource dataSource;
    private final com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository;
    private final UsageService usageService;

    public UsageExportService(DataSource dataSource,
                              com.lightai.storage.trace.JdbcUsageAggregateRepository aggregateRepository,
                              UsageService usageService) {
        this.dataSource = dataSource;
        this.aggregateRepository = aggregateRepository;
        this.usageService = usageService;
    }

    public ResponseEntity<StreamingResponseBody> export(RequestContext context,
                                                        Map<String, List<String>> params) {
        com.lightai.admin.web.RequestPermissions.require(context, Permissions.USAGE_EXPORT);
        UsageQuery query = UsageQueryParser.parse(params);
        ResolvedQuery resolved = usageService.prepare(context, query);

        long total;
        try (Connection connection = dataSource.getConnection()) {
            total = aggregateRepository.countExportRows(connection, filterOf(resolved, query),
                    UsageService.dimensionColumn(query.groupBy()));
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

        String filename = "usage-" + query.granularity() + "-" + query.groupBy() + "-"
                + FILE_TIME.format(resolved.startAt().toInstant()) + "-"
                + FILE_TIME.format(resolved.endAt().toInstant()) + ".csv";
        long deadline = System.nanoTime() + Duration.ofMillis(MAX_DURATION_MS).toNanos();
        StreamingResponseBody body = outputStream -> streamRows(outputStream, resolved, query,
                deadline);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=" + filename)
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private AggregateFilter filterOf(ResolvedQuery resolved, UsageQuery query) {
        return new AggregateFilter(query.granularity(), resolved.startAt(), resolved.endAt(),
                query.applications(), query.projects(), query.tenants(), query.aliasIds(),
                query.providerIds(), query.providerModelIds(), query.credentialPoolIds(),
                query.credentialIds(), query.traceStatuses(), query.errorCodes(),
                query.usageSources(), query.requestedStream(), query.currency());
    }

    private void streamRows(OutputStream outputStream, ResolvedQuery resolved, UsageQuery query,
                            long deadline) {
        try (Connection connection = dataSource.getConnection()) {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream,
                    StandardCharsets.UTF_8), 64 * 1024);
            CsvStreamWriter.writeBom(writer);
            CsvStreamWriter.writeRow(writer, HEADERS);
            List<ExportRow> rows = aggregateRepository.exportRows(connection,
                    filterOf(resolved, query), UsageService.dimensionColumn(query.groupBy()));
            for (ExportRow row : rows) {
                if (System.nanoTime() > deadline) {
                    throw new ExportDeadlineExceeded();
                }
                writeRow(writer, resolved, query, row);
            }
            writer.flush();
        } catch (ExportDeadlineExceeded ignored) {
            // 60 秒执行上限：停止生成，不伪造完整文件
        } catch (IOException ignored) {
            // 客户端断开：取消游标停止生成
        } catch (Exception ignored) {
            // 响应头已发出，无法回写错误码
        }
    }

    private void writeRow(Writer writer, ResolvedQuery resolved, UsageQuery query, ExportRow row)
            throws IOException {
        List<String> cells = new ArrayList<>(HEADERS.size());
        cells.add(raw(row.bucketStart()));
        cells.add(raw(row.bucketEnd()));
        cells.add(CsvStreamWriter.text(query.groupBy()));
        cells.add(CsvStreamWriter.text(row.dimensionValue()));
        cells.add(CsvStreamWriter.text(dimensionName(query, row)));
        cells.add(String.valueOf(row.requestCount()));
        cells.add(String.valueOf(row.successCount()));
        cells.add(String.valueOf(row.failureCount()));
        cells.add(raw(UsageService.rate(row.successCount(),
                row.successCount() + row.failureCount())));
        cells.add(String.valueOf(row.attemptCount()));
        cells.add(String.valueOf(row.initialCount()));
        cells.add(String.valueOf(row.retryCount()));
        cells.add(String.valueOf(row.credentialFailoverCount()));
        cells.add(String.valueOf(row.fallbackCount()));
        cells.add(String.valueOf(row.halfOpenProbeCount()));
        cells.add(String.valueOf(row.actualTokens()));
        cells.add(String.valueOf(row.estimatedTokens()));
        cells.add(String.valueOf(row.totalTokens()));
        cells.add(raw(row.inputCost()));
        cells.add(raw(row.outputCost()));
        cells.add(raw(row.totalCost()));
        cells.add(CsvStreamWriter.text(row.currency()));
        CsvStreamWriter.writeRow(writer, cells);
    }

    private static String dimensionName(UsageQuery query, ExportRow row) {
        String name = switch (query.groupBy()) {
            case "ALIAS" -> row.dimensionNames() == null ? null
                    : row.dimensionNames().get("alias");
            case "PROVIDER" -> row.dimensionNames() == null ? null
                    : row.dimensionNames().get("provider");
            case "PROVIDER_MODEL" -> row.dimensionNames() == null ? null
                    : row.dimensionNames().get("provider_model");
            case "CREDENTIAL_POOL" -> row.dimensionNames() == null ? null
                    : row.dimensionNames().get("credential_pool");
            case "CREDENTIAL" -> row.dimensionNames() == null ? null
                    : row.dimensionNames().get("credential");
            default -> row.dimensionValue();
        };
        return name == null || name.isBlank() ? "未设置" : name;
    }

    private static String raw(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class ExportDeadlineExceeded extends RuntimeException {
    }
}
