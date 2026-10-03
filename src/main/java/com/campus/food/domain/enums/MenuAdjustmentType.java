package com.campus.food.domain.enums;

/** 菜单调整类型 */
public enum MenuAdjustmentType {
    INGREDIENT_SUBSTITUTE, // 改用其他食材
    DISH_REPLACED,         // 更换菜品
    SERVING_REDUCED,       // 减量供餐
    CANCELLED              // 取消该菜
}
