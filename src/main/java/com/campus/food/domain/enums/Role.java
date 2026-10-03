package com.campus.food.domain.enums;

/** 系统角色 */
public enum Role {
    INSPECTOR,       // 验收员（到货验收、录入冷链取证信息）
    PROCUREMENT,     // 采购负责人
    CANTEEN,         // 食堂
    QUALITY,         // 质检人员（冷链复检、降级入库前复查）
    SCHOOL_ADMIN     // 校方管理
}
