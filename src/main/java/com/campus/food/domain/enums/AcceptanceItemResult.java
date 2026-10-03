package com.campus.food.domain.enums;

/** 验收明细结果 */
public enum AcceptanceItemResult {
    QUALIFIED,           // 合格入库
    PARTIAL,             // 部分合格
    REJECTED,            // 拒收退货
    DOWNGRADED,          // 降级使用（待审批）
    CONDITIONAL_ACCEPT,  // 已备餐无法拒收，附条件接收并索赔
    HELD                 // 冷链温度超标暂扣，待食堂处置（拒收/降级/复检）
}
