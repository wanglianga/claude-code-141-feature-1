package com.campus.food.domain;

import com.campus.food.domain.enums.MenuAdjustmentStatus;
import com.campus.food.domain.enums.MenuAdjustmentType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 菜单调整记录：冷链拒收影响当天/近期菜单时，
 * 系统同步为每个受影响餐次生成调整建议（换食材/换菜/减量/取消），食堂确认后生效。
 */
@Entity
@Table(name = "menu_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class MenuAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String adjustmentNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "cold_chain_case_id")
    private ColdChainCase coldChainCase;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MenuAdjustmentType adjustmentType;

    /** 原菜品 / 调整后菜品 */
    @Column(length = 128)
    private String originalDish;

    @Column(length = 128)
    private String adjustedDish;

    /** 缺失食材 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "original_ingredient_id")
    private Ingredient originalIngredient;

    /** 建议替代食材（换菜/取消时可为空） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "substitute_ingredient_id")
    private Ingredient substituteIngredient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MenuAdjustmentStatus status = MenuAdjustmentStatus.PROPOSED;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime confirmedAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "confirmed_by")
    private AppUser confirmedBy;
}
