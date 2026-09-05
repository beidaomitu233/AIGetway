package com.lightai.client.management;

/**
 * 带影响确认的命令体：disable/delete 必须回传确认前取得的
 * impact_version；引用关系变化后校验失败返回 IMPACT_ANALYSIS_EXPIRED。
 */
public record ImpactConfirmCommand(long version, String confirmedImpactVersion) {

    public ImpactConfirmCommand {
        if (version < 1) {
            throw new IllegalArgumentException("version 必须为正整数");
        }
        if (confirmedImpactVersion == null || confirmedImpactVersion.isBlank()) {
            throw new IllegalArgumentException("confirmed_impact_version 必填");
        }
    }
}
