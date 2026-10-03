package com.campus.food.domain.enums;

/** 系统角色 */
public enum Role {
    INSPECTOR,       // 验收员
    PROCUREMENT,     // 采购负责人
    CANTEEN,         // 食堂
    QUALITY,         // 质检人员（降级复查 / 复检）
    SCHOOL_ADMIN     // 校方管理
}
