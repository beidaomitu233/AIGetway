package com.lightai.client.management;

/**
 * 管理写操作通用命令体：更新/启停必须提交 version（BACKEND_PLAN 4.2.9）；
 * 停用与删除额外要求 confirmed_impact_version（引用关系摘要票据）。
 */
public record VersionCommand(long version) {

    public VersionCommand {
        if (version < 1) {
            throw new IllegalArgumentException("version 必须为正整数");
        }
    }
}
