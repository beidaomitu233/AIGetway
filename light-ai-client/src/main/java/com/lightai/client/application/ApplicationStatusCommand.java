package com.lightai.client.application;

/** 应用状态变更使用乐观版本，目标状态仅允许 ACTIVE、DISABLED 或 ARCHIVED。 */
public record ApplicationStatusCommand(String status, long version, String reason) {
}
