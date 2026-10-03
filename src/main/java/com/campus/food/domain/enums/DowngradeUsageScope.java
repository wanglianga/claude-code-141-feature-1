package com.campus.food.domain.enums;

/** 冷链超标降级使用的允许用途（该批食材禁止进入学生餐即食链路） */
public enum DowngradeUsageScope {
    STAFF_MEAL,        // 仅限教职工餐
    NON_READY_TO_EAT   // 仅限非即食加工（需充分加热后使用）
}
