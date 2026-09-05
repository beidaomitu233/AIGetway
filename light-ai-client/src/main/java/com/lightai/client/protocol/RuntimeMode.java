package com.lightai.client.protocol;

/**
 * 部署形态枚举（C-003）：source_mode 与管理测试 invocation_source 分离，
 * 运行模式不混入部署语义。
 */
public enum RuntimeMode {
    LOCAL_RUNTIME,
    EMBEDDED,
    STANDALONE_SERVER
}
