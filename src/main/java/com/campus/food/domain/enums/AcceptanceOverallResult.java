package com.campus.food.domain.enums;

/** 验收单总体结果 */
public enum AcceptanceOverallResult {
    QUALIFIED,          // 全部合格
    PARTIAL_QUALIFIED,  // 部分合格
    REJECTED,           // 整批拒收
    PENDING             // 冷链温度超标，问题食材暂扣待食堂处置（拒收/降级/复检）
}
