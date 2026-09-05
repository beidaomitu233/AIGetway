package com.lightai.spi.adapter;

import com.lightai.client.bootstrap.AdapterDeclaration;
import java.util.List;

/**
 * Adapter 元数据来源 SPI：Bootstrap 由此输出已加载 Adapter 的非敏感不可变声明。
 * 无实现（未装配任何 Provider Adapter）时 Bootstrap 省略 adapters 字段。
 */
public interface AdapterMetadataSource {

    List<AdapterDeclaration> declarations();
}
