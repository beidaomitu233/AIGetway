package com.lightai.client.application;

/** 撤销是不可恢复操作。 */
public record ApplicationKeyRevokeCommand(long version, String reason) {
}
