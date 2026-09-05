package com.lightai.spi.auth;

/**
 * 管理身份提供 SPI：由部署认证适配实现（Embedded 宿主映射、Standalone 身份集成）。
 * 缺省实现为默认拒绝匿名（PROJECT_DOCUMENT 第 6 节），不提供默认管理员密码。
 * 角色映射到 PROJECT_DOCUMENT 2.4.1 四角色；数据范围由 application_scope 表达。
 */
public interface AuthContextProvider {

    /** 解析当前请求身份；无法建立受信身份时返回未认证上下文，由调用方统一 403。 */
    AuthContext resolve(AuthRequest request);
}
