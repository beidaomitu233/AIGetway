package com.lightai.client.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 金额反序列化：接受十进制字符串与 JSON 数值，统一转 BigDecimal；
 * 空串按 null 处理（可空金额字段按 null 清除）。
 */
public final class LenientBigDecimalDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return new BigDecimal(text.trim());
    }

    @Override
    public BigDecimal getNullValue(DeserializationContext ctxt) {
        return null;
    }
}
