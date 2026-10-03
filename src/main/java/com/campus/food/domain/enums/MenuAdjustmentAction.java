package com.campus.food.domain.enums;

/** 冷链超标拒收后的当天菜单调整动作 */
public enum MenuAdjustmentAction {
    REPLACE_DISH,   // 换菜
    SUBSTITUTE,     // 同菜换料
    CANCEL_MEAL,    // 停供该餐
    PORTION_REDUCE  // 减量供餐
}
