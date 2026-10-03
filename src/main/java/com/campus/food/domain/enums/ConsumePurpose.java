package com.campus.food.domain.enums;

/**
 * 领用用途（冷链降级批次受用途管制）：
 * STUDENT_MEAL 学生餐即食——禁止领用受限批次；
 * STAFF_MEAL 教职工餐、NON_READY_TO_EAT 非即食加工——允许领用受限批次。
 */
public enum ConsumePurpose {
    STUDENT_MEAL,
    STAFF_MEAL,
    NON_READY_TO_EAT
}
