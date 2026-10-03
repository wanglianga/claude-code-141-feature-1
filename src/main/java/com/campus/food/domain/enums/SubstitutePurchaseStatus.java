package com.campus.food.domain.enums;

/** 替代采购单状态 */
public enum SubstitutePurchaseStatus {
    PLACED,    // 已下单（紧急替代采购）
    RECEIVED,  // 已到货入库
    CANCELLED  // 已取消（库存可覆盖或菜单已调整，无需采购）
}
