package com.campus.food.domain.enums;

/**
 * 冷链温度超标处置案件状态。
 * 流转：
 * ON_HOLD --食堂决定--> REJECTED（终态）/ RETEST_PENDING / DOWNGRADE_PENDING
 * RETEST_PENDING --复检合格--> ACCEPTED（终态）；复检失败--> RETEST_FAILED --再决定--> ...
 * DOWNGRADE_PENDING --采购批准--> DOWNGRADE_APPROVED --质检复查通过--> ACCEPTED（终态）
 * DOWNGRADE_PENDING --采购驳回--> DOWNGRADE_REJECTED（终态，转退货）
 * DOWNGRADE_APPROVED --质检复查不通过--> REJECTED（终态）
 */
public enum ColdChainCaseStatus {
    ON_HOLD,              // 暂扣待食堂处置
    REJECTED,             // 已拒收（退货+索赔+替代采购+菜单调整）
    RETEST_PENDING,       // 等待复检
    RETEST_FAILED,        // 复检失败，可再次处置
    DOWNGRADE_PENDING,    // 降级使用待采购审批折价
    DOWNGRADE_APPROVED,   // 折价已批准，待质检复查
    DOWNGRADE_REJECTED,   // 降级被驳回，已转退货
    ACCEPTED              // 已结案入库（复检合格放行 / 降级复查通过）
}
