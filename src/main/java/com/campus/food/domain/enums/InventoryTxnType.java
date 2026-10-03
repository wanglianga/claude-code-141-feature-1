package com.campus.food.domain.enums;

/** 库存流水类型 */
public enum InventoryTxnType {
    IN,             // 入库
    OUT,            // 出库（领用）
    RETURN_OUT,     // 退货出库
    ADJUST,         // 盘点调整
    RESTRICTED_IN,  // 限制用途入库（冷链降级，教职工餐/非即食加工）
    RESTRICTED_OUT  // 限制用途领用出库
}
