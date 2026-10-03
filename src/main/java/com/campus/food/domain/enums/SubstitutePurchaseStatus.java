package com.campus.food.domain.enums;

/** 冷链超标拒收后生成的替代采购单状态 */
public enum SubstitutePurchaseStatus {
    PLACED,    // 已下单
    RECEIVED,  // 已到货验收
    CANCELLED  // 已取消（复检合格，原批次恢复使用）
}
