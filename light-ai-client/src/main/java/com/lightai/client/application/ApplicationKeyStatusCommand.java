package com.lightai.client.application;

/** 应用密钥启停命令；撤销使用独立不可逆操作。 */
public record ApplicationKeyStatusCommand(String status, long version, String reason) {
}
