package com.lightai.client.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 协议对象共享 JSON 配置：全字段 snake_case、反序列化拒绝未知字段、
 * OffsetDateTime 以 ISO 8601 带偏移传输、金额以十进制字符串往返不丢精度。
 */
public final class ProtocolJson {

    private static final ObjectMapper PROTOCOL = base()
            .registerModule(new SimpleModule("light-ai-protocol")
                    .addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer())
                    .addDeserializer(BigDecimal.class, new LenientBigDecimalDeserializer()))
            // SDK/客户端读取协议响应时对新增字段保持向前兼容；写入路径必须用 strictCommands
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 管理命令反序列化：未知字段直接失败，禁止静默兼容。 */
    private static final ObjectMapper STRICT_COMMANDS = PROTOCOL.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ProtocolJson() {
    }

    public static ObjectMapper protocol() {
        return PROTOCOL;
    }

    /** 严格模式：用于解析管理命令等不可含未知键的请求体。 */
    public static ObjectMapper strictCommands() {
        return STRICT_COMMANDS;
    }

    private static ObjectMapper base() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
