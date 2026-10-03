package com.campus.food.domain;

import com.campus.food.domain.enums.MenuAdjustmentAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 菜单调整记录：冷链超标拒收影响当天菜单时，
 * 食堂对受影响餐次采取换菜 / 同菜换料 / 停供 / 减量等调整并留痕。
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

    /** 受影响餐次 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MenuAdjustmentAction action;

    /** 调整前菜品 */
    @Column(nullable = false, length = 128)
    private String originalDishName;

    /** 调整说明（换菜后的菜品 / 替代食材 / 减量份数等） */
    @Column(length = 512)
    private String adjustedDescription;

    /** 影响供餐份数 */
    @Column(nullable = false)
    private Integer affectedServings = 0;

    /** 调整人（食堂） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "adjusted_by")
    private AppUser adjustedBy;

    @Column(nullable = false)
    private LocalDateTime adjustedAt = LocalDateTime.now();

    @Column(length = 512)
    private String note;
}
