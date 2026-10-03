package com.campus.food.domain.enums;

/** 冷链超标批次的质检复查 / 复检结论状态 */
public enum ColdChainReviewStatus {
    PENDING_REVIEW,  // 等待质检人员复查
    PENDING_LAB,     // 已取样，等待实验室复检结果
    PASSED,          // 复查/复检合格，放行
    FAILED           // 复查/复检不合格，转拒收
}
