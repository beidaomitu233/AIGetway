package com.lightai.admin.query;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.FieldIssue;
import com.lightai.client.error.LightAiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 管理列表查询基座（BE-004）：分页与排序白名单统一解析。
 * 口径（PRD 4.3.5.5）：page 从 1 开始；page_size 默认 20，范围 1—100；
 * sort 为「列 方向」，列必须命中白名单，方向仅 asc/desc，默认 asc；
 * 未指定 sort 时使用调用方给定的默认排序（默认值同样必须命中白名单）。
 * 非法分页或排序返回 FIELD_VALIDATION_FAILED；排序列白名单同时阻断排序注入。
 */
public final class ListQuerySupport {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    /** 归一化查询：sort 已校验为白名单内「列 方向」，可安全进入 ORDER BY。 */
    public record ListQuery(int page, int pageSize, String sort) {

        public long offset() {
            return (long) (page - 1) * pageSize;
        }

        public int limit() {
            return pageSize;
        }
    }

    private ListQuerySupport() {
    }

    /**
     * 解析并校验列表查询参数。
     *
     * @param rawPage          原始 page 参数（可空）
     * @param rawPageSize      原始 page_size 参数（可空）
     * @param rawSort          原始 sort 参数（可空）
     * @param allowedColumns   排序列白名单（snake_case 数据列）
     * @param defaultSort      未指定 sort 时的默认排序，如 "updated_at desc"
     */
    public static ListQuery parse(String rawPage, String rawPageSize, String rawSort,
                                  Set<String> allowedColumns, String defaultSort) {
        List<FieldIssue> issues = new ArrayList<>();

        int page = DEFAULT_PAGE;
        if (rawPage != null && !rawPage.isBlank()) {
            Integer parsed = parseInt(rawPage.trim());
            if (parsed == null) {
                issues.add(new FieldIssue("page", "INVALID", "page 必须是正整数"));
            } else if (parsed < 1) {
                issues.add(new FieldIssue("page", "INVALID", "page 从 1 开始"));
            } else {
                page = parsed;
            }
        }

        int pageSize = DEFAULT_PAGE_SIZE;
        if (rawPageSize != null && !rawPageSize.isBlank()) {
            Integer parsed = parseInt(rawPageSize.trim());
            if (parsed == null) {
                issues.add(new FieldIssue("page_size", "INVALID", "page_size 必须是正整数"));
            } else if (parsed < 1 || parsed > MAX_PAGE_SIZE) {
                issues.add(new FieldIssue("page_size", "INVALID",
                        "page_size 范围 1—" + MAX_PAGE_SIZE));
            } else {
                pageSize = parsed;
            }
        }

        String sort = normalizeSort(rawSort, defaultSort, allowedColumns, issues);

        if (!issues.isEmpty()) {
            throw new LightAiException(ErrorCode.FIELD_VALIDATION_FAILED, "列表查询参数不合法", issues);
        }
        return new ListQuery(page, pageSize, sort);
    }

    private static String normalizeSort(String rawSort, String defaultSort,
                                        Set<String> allowedColumns, List<FieldIssue> issues) {
        String candidate = rawSort == null || rawSort.isBlank() ? defaultSort : rawSort.trim();
        if (candidate == null || candidate.isBlank()) {
            issues.add(new FieldIssue("sort", "REQUIRED", "未提供排序且无默认排序"));
            return null;
        }
        String[] parts = candidate.split("\\s+");
        String column = parts[0].toLowerCase();
        String direction = parts.length > 1 ? parts[1].toLowerCase() : "asc";
        if (!allowedColumns.contains(column)) {
            issues.add(new FieldIssue("sort", "INVALID", "排序列不在白名单: " + column));
            return null;
        }
        if (!direction.equals("asc") && !direction.equals("desc")) {
            issues.add(new FieldIssue("sort", "INVALID", "排序方向仅支持 asc/desc"));
            return null;
        }
        return column + " " + direction;
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
