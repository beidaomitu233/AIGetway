package com.lightai.runtime.settlement;

import java.math.BigDecimal;

/**
 * 调用开始时的价格快照（BE-030）：改价不影响历史 Attempt 结算；
 * 金额 NUMERIC 精度，禁止 double 与汇率换算。
 */
public record PriceSnapshot(
        String modelId,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        int priceUnit,
        String currency) {

    public PriceSnapshot {
        if (priceUnit <= 0) {
            throw new IllegalArgumentException("price_unit 必须为正整数");
        }
    }
}
