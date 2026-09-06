package com.lightai.runtime.settlement;

import com.lightai.client.chat.CostInfo;
import com.lightai.client.chat.Usage;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Attempt 结算（BE-030）：每 Attempt 以调用开始价格快照计算
 * input_tokens×input_price/price_unit 与 output 同式；
 * 金额 8 位小数 HALF_UP，在 Attempt 组件上各舍入一次再求和（BACKEND_PLAN 第 3 节）。
 * 失败请求也结算；Usage 缺失按估算生成 ESTIMATED。
 */
public final class UsageSettlement {

    public static final int MONEY_SCALE = 8;

    private UsageSettlement() {
    }

    /** Attempt 用量与费用。 */
    public record AttemptSettlement(Usage usage, CostInfo cost) {
    }

    /**
     * 结算一次 Attempt。
     *
     * @param actualInputTokens  Provider 实际输入 Token（可空）
     * @param actualOutputTokens Provider 实际输出 Token（可空）
     * @param estimatedInput     估算输入 Token（Usage 缺失时使用）
     * @param estimatedOutput    估算输出 Token（Usage 缺失时使用）
     */
    public static AttemptSettlement settle(PriceSnapshot snapshot, Long actualInputTokens,
                                           Long actualOutputTokens, long estimatedInput, long estimatedOutput) {
        boolean actual = actualInputTokens != null && actualOutputTokens != null;
        // 部分缺失按估算补齐：缺失部分由 TokenEstimator 估算，来源整体标记 ESTIMATED
        long input = actualInputTokens != null ? actualInputTokens : estimatedInput;
        long output = actualOutputTokens != null ? actualOutputTokens : estimatedOutput;
        Usage usage = Usage.of(input, output,
                actual ? Usage.SOURCE_ACTUAL : Usage.SOURCE_ESTIMATED);
        BigDecimal inputComponent = component(snapshot.inputPrice(), input, snapshot.priceUnit());
        BigDecimal outputComponent = component(snapshot.outputPrice(), output, snapshot.priceUnit());
        BigDecimal amount = inputComponent.add(outputComponent);
        CostInfo cost = new CostInfo(amount, snapshot.currency(), !actual);
        return new AttemptSettlement(usage, cost);
    }

    /** 单组件舍入：tokens×price/unit，8 位小数 HALF_UP。 */
    static BigDecimal component(BigDecimal price, long tokens, int priceUnit) {
        BigDecimal safePrice = price == null ? BigDecimal.ZERO : price;
        return safePrice.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(priceUnit), MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
