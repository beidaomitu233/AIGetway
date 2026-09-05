package com.lightai.runtime;

import com.lightai.client.chat.CostInfo;
import com.lightai.client.chat.Usage;
import com.lightai.runtime.settlement.PriceSnapshot;
import com.lightai.runtime.settlement.UsageSettlement;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 结算语义（BE-030）：组件各舍入一次再求和、8 位小数 HALF_UP、ACTUAL/ESTIMATED。 */
class UsageSettlementTest {

    private static final PriceSnapshot SNAPSHOT = new PriceSnapshot(
            "gpt-test", new BigDecimal("0.00000015"), new BigDecimal("0.00000060"), 1000, "USD");

    @Test
    void actualUsageRoundsPerComponent() {
        UsageSettlement.AttemptSettlement settlement = UsageSettlement.settle(
                SNAPSHOT, 1234567L, 2345678L, 0, 0);
        Usage usage = settlement.usage();
        assertThat(usage.promptTokens()).isEqualTo(1234567L);
        assertThat(usage.completionTokens()).isEqualTo(2345678L);
        assertThat(usage.totalTokens()).isEqualTo(1234567L + 2345678L);
        assertThat(usage.source()).isEqualTo(Usage.SOURCE_ACTUAL);
        // input: 0.00000015*1234567/1000 = 0.00018518505 → 0.00018519（HALF_UP 8位）
        // output: 0.00000060*2345678/1000 = 0.0014074068 → 0.00140741
        CostInfo cost = settlement.cost();
        assertThat(cost.amount().scale()).isEqualTo(8);
        assertThat(cost.amount().toPlainString())
                .isEqualTo(new BigDecimal("0.00018519").add(new BigDecimal("0.00140741")).toPlainString());
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.estimated()).isFalse();
    }

    @Test
    void missingUsageFallsBackToEstimate() {
        UsageSettlement.AttemptSettlement settlement = UsageSettlement.settle(SNAPSHOT, null, null, 500, 100);
        assertThat(settlement.usage().source()).isEqualTo(Usage.SOURCE_ESTIMATED);
        assertThat(settlement.usage().promptTokens()).isEqualTo(500);
        assertThat(settlement.usage().completionTokens()).isEqualTo(100);
        assertThat(settlement.cost().estimated()).isTrue();
        // 500*0.00000015/1000=0.000000075 → 0.00000008；100*0.00000060/1000=0.00000006
        assertThat(settlement.cost().amount().toPlainString())
                .isEqualTo(new BigDecimal("0.00000008").add(new BigDecimal("0.00000006")).toPlainString());
    }

    @Test
    void partialUsageMergesActualAndEstimate() {
        UsageSettlement.AttemptSettlement settlement = UsageSettlement.settle(SNAPSHOT, 1000L, null, 0, 50);
        assertThat(settlement.usage().source()).isEqualTo(Usage.SOURCE_ESTIMATED);
        assertThat(settlement.usage().promptTokens()).isEqualTo(1000L);
        assertThat(settlement.usage().completionTokens()).isEqualTo(50L);
    }
}
