package com.campus.food.domain.enums;

/** 冷链温度超标后食堂的处置方式 */
public enum ColdChainDisposition {
    REJECT,      // 拒收
    DOWNGRADE,   // 降级为非学生餐使用
    RETEST       // 等待复检
}
