package com.campus.food.domain.enums;

/** 库存允许的使用范围（降级冷链食材只能用于非学生餐或非即食加工） */
public enum InventoryUsageScope {
    NORMAL,        // 正常，可用于学生餐
    STAFF_MEAL,    // 仅限教职工餐
    COOKED_ONLY    // 仅限非即食加工（须充分加热后供教职工餐）
}
