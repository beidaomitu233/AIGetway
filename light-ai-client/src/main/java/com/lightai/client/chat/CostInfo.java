package com.lightai.client.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lightai.client.json.PlainStringBigDecimalSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;

/**
 * 金额组件：十进制字符串传输（BACKEND_PLAN 金额口径），
 * 每 Attempt 各自舍入后求和，禁止 double 与汇率换算。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CostInfo(
        @JsonSerialize(using = PlainStringBigDecimalSerializer.class) BigDecimal amount,
        String currency,
        boolean estimated) {
}
