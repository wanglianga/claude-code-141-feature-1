package com.campus.food.domain.enums;

/** 冷链温度超标处置方式 */
public enum ColdChainDisposition {
    PENDING,       // 待处置（等待复检）
    REJECTION,     // 拒收
    DOWNGRADE,     // 降级为非学生餐使用
    RECHECK        // 等待复检
}
