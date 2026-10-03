package com.campus.food.service;

import com.campus.food.domain.enums.AnomalyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenaltyRulesTest {

    @Test
    void lateDeliveryHasMinimumPenalty() {
        // 基数 1000 * 5% = 50 < 200，按最低 200 计
        assertEquals(new BigDecimal("200.00"),
                PenaltyRules.penaltyFor(AnomalyType.LATE_DELIVERY, new BigDecimal("1000")));
        // 基数 10000 * 5% = 500
        assertEquals(new BigDecimal("500.00"),
                PenaltyRules.penaltyFor(AnomalyType.LATE_DELIVERY, new BigDecimal("10000")));
    }

    @Test
    void coldChainHasMinimumPenalty() {
        assertEquals(new BigDecimal("300.00"),
                PenaltyRules.penaltyFor(AnomalyType.COLD_CHAIN_BREACH, new BigDecimal("100")));
        // 冷链货值 10000 * 5% = 500，超过最低 300 时按比例计罚（与拒收索赔共同进入结算）
        assertEquals(new BigDecimal("500.00"),
                PenaltyRules.penaltyFor(AnomalyType.COLD_CHAIN_BREACH, new BigDecimal("10000")));
    }

    @Test
    void shortDeliveryIsTenPercent() {
        assertEquals(new BigDecimal("12.00"),
                PenaltyRules.penaltyFor(AnomalyType.SHORT_DELIVERY, new BigDecimal("120")));
    }

    @Test
    void missingDocumentsIsFlat() {
        assertEquals(new BigDecimal("100.00"),
                PenaltyRules.penaltyFor(AnomalyType.MISSING_DOCUMENTS, null));
    }

    @Test
    void noPenaltyForPreparedAndMismatch() {
        assertEquals(new BigDecimal("0.00"),
                PenaltyRules.penaltyFor(AnomalyType.CANNOT_REJECT_PREPARED, new BigDecimal("999")));
        assertEquals(new BigDecimal("0.00"),
                PenaltyRules.penaltyFor(AnomalyType.SETTLEMENT_MISMATCH, new BigDecimal("999")));
    }
}
