package com.lightai.client.error;

/** 字段级错误明细，用于 FIELD_VALIDATION_FAILED 的 errors 数组。 */
public record FieldIssue(String field, String code, String message) {
}
