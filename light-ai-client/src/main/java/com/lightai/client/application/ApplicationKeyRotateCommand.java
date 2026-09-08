package com.lightai.client.application;

/** 轮换后旧密钥立即失效，新原文只返回一次。 */
public record ApplicationKeyRotateCommand(long version, String reason) {
}
