package com.lightai.admin.audit;

import com.lightai.admin.query.ListQuerySupport;
import com.lightai.admin.web.RequestContext;
import com.lightai.admin.web.RequestPermissions;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;
import com.lightai.client.paging.PageResult;
import com.lightai.client.security.AuditLogDetail;
import com.lightai.client.security.AuditLogListItem;
import com.lightai.storage.audit.AuditQueryRepository;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * 审计查询与导出（BE-045）：筛选、稳定排序、分页；CSV 导出 UTF-8 BOM、
 * 公式字符转义（=+-@ 前缀加 '）、100000 行上限与 60 秒限制由调用方执行；
 * request_id 精确查询可跨留存期。
 */
public class AuditQueryService {

    public static final Set<String> SORTABLE = Set.of(
            "created_at", "request_id", "operator_id", "action", "entity_type", "result");
    private static final int EXPORT_ROW_LIMIT = 100000;

    private final DataSource dataSource;
    private final AuditQueryRepository repository;

    public AuditQueryService(DataSource dataSource, AuditQueryRepository repository) {
        this.dataSource = dataSource;
        this.repository = repository;
    }

    public PageResult<AuditLogListItem> list(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.AUDIT_VIEW);
        String requestId = params.get("request_id");
        String operatorId = params.get("operator_id");
        String action = params.get("action");
        String entityType = params.get("entity_type");
        String result = params.get("result");
        OffsetDateTime startedFrom = parseTime(params.get("started_from"));
        OffsetDateTime startedTo = parseTime(params.get("started_to"));
        ListQuerySupport.ListQuery query = ListQuerySupport.parse(params.get("page"),
                params.get("page_size"), params.get("sort"), SORTABLE, "created_at desc");
        try (Connection connection = dataSource.getConnection()) {
            Filter filter = buildFilter(requestId, operatorId, action, entityType, result,
                    startedFrom, startedTo);
            List<AuditLogListItem> items = repository.list(connection, filter.sql(), filter.values(),
                    query.sort(), query.offset(), query.limit()).stream()
                    .map(AuditQueryService::toListItem)
                    .toList();
            long total = repository.count(connection, filter.sql(), filter.values());
            OffsetDateTime now = OffsetDateTime.now();
            return new PageResult<>(items, total, query.page(), query.pageSize(), query.sort(), now, now);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.AUDIT_DATA_UNAVAILABLE, "审计数据当前无法完整读取");
        }
    }

    public AuditLogDetail get(RequestContext context, UUID id) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.AUDIT_VIEW);
        try (Connection connection = dataSource.getConnection()) {
            AuditQueryRepository.AuditQueryRow row = repository.find(connection, id)
                    .orElseThrow(() -> new LightAiException(ErrorCode.OBJECT_NOT_FOUND, "审计记录不存在"));
            return toDetail(row);
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.AUDIT_DATA_UNAVAILABLE, "审计详情读取失败");
        }
    }

    /** 流式导出：先检查行数（EXPORT_TOO_LARGE），再逐批游标写出 UTF-8 BOM CSV。 */
    public String exportCsv(RequestContext context, Map<String, String> params) {
        RequestPermissions.require(context, com.lightai.client.protocol.Permissions.AUDIT_EXPORT);
        String requestId = params.get("request_id");
        String operatorId = params.get("operator_id");
        String action = params.get("action");
        String entityType = params.get("entity_type");
        String result = params.get("result");
        OffsetDateTime startedFrom = parseTime(params.get("started_from"));
        OffsetDateTime startedTo = parseTime(params.get("started_to"));
        try (Connection connection = dataSource.getConnection()) {
            Filter filter = buildFilter(requestId, operatorId, action, entityType, result,
                    startedFrom, startedTo);
            long total = repository.count(connection, filter.sql(), filter.values());
            if (total > EXPORT_ROW_LIMIT) {
                throw new LightAiException(ErrorCode.EXPORT_TOO_LARGE,
                        "当前筛选预计导出超过 " + EXPORT_ROW_LIMIT + " 行，需要缩小时间或业务范围");
            }
            StringWriter buffer = new StringWriter();
            Writer writer = new BufferedWriter(buffer);
            writer.write('\ufeff');
            writer.write("created_at,request_id,operator_id,action,entity_type,entity_id,result,error_code\n");
            long offset = 0;
            int batch = 1000;
            while (true) {
                List<AuditQueryRepository.AuditQueryRow> rows = repository.list(connection,
                        filter.sql(), filter.values(), "created_at desc, id desc", offset, batch);
                for (AuditQueryRepository.AuditQueryRow row : rows) {
                    writer.write(csvLine(row));
                }
                offset += rows.size();
                if (rows.size() < batch) {
                    break;
                }
            }
            writer.flush();
            return buffer.toString();
        } catch (LightAiException e) {
            throw e;
        } catch (Exception e) {
            throw new LightAiException(ErrorCode.AUDIT_DATA_UNAVAILABLE, "审计导出失败");
        }
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    private Filter buildFilter(String requestId, String operatorId, String action, String entityType,
                               String result, OffsetDateTime startedFrom, OffsetDateTime startedTo) {
        StringBuilder sql = new StringBuilder();
        List<Object> values = new ArrayList<>();
        if (requestId != null && !requestId.isBlank()) {
            sql.append("request_id = ?");
            values.add(requestId.trim());
        }
        if (operatorId != null && !operatorId.isBlank()) {
            appendAnd(sql, "operator_id = ?");
            values.add(operatorId.trim());
        }
        if (action != null && !action.isBlank()) {
            appendAnd(sql, "action = ?");
            values.add(action.trim());
        }
        if (entityType != null && !entityType.isBlank()) {
            appendAnd(sql, "entity_type = ?");
            values.add(entityType.trim());
        }
        if (result != null && !result.isBlank()) {
            appendAnd(sql, "result = ?");
            values.add(result.trim());
        }
        if (startedFrom != null) {
            appendAnd(sql, "created_at >= ?");
            values.add(startedFrom);
        }
        if (startedTo != null) {
            appendAnd(sql, "created_at < ?");
            values.add(startedTo);
        }
        return new Filter(sql.toString().trim(), values);
    }

    private static void appendAnd(StringBuilder sql, String clause) {
        sql.append(sql.length() > 0 ? " AND " : "").append(clause);
    }

    private static String csvLine(AuditQueryRepository.AuditQueryRow row) {
        StringBuilder line = new StringBuilder();
        append(line, row.createdAt() == null ? "" : row.createdAt().toString());
        append(line, row.requestId());
        append(line, row.operatorId());
        append(line, row.action());
        append(line, row.entityType());
        append(line, row.entityId());
        append(line, row.result());
        append(line, row.errorCode());
        return line.append("\n").toString();
    }

    /** CSV 注入转义：=+-@ 开头前置 '；逗号与引号包裹。 */
    static String csvCell(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.startsWith("=") || escaped.startsWith("+") || escaped.startsWith("-")
                || escaped.startsWith("@")) {
            escaped = "'" + escaped;
        }
        return "\"" + escaped + "\"";
    }

    private static void append(StringBuilder line, String value) {
        if (line.length() > 0 && line.charAt(line.length() - 1) != '\n') {
            line.append(',');
        }
        line.append(csvCell(value));
    }

    private static AuditLogListItem toListItem(AuditQueryRepository.AuditQueryRow row) {
        return new AuditLogListItem(row.id().toString(), row.createdAt(), row.requestId(), row.operatorId(),
                row.action(), row.entityType(), row.entityId(), row.result(), row.errorCode(),
                List.of());
    }

    private static AuditLogDetail toDetail(AuditQueryRepository.AuditQueryRow row) {
        return new AuditLogDetail(row.id().toString(), row.createdAt(), row.requestId(), row.operatorId(),
                row.action(), row.entityType(), row.entityId(), row.result(),
                parseChanges(row.changesJson()), row.errorCode(), row.errorSummary(),
                row.sourceMode(), row.sourceIpMasked());
    }

    private static List<com.lightai.client.changes.FieldChange> parseChanges(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return com.lightai.client.json.ProtocolJson.protocol().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.lightai.client.changes.FieldChange>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private record Filter(String sql, List<Object> values) {
    }
}
