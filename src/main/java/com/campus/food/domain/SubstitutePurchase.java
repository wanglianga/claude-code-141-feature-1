package com.campus.food.domain;

import com.campus.food.domain.enums.SubstitutePurchaseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 替代采购记录：冷链超标拒收影响当天菜单时，
 * 为保证供餐而紧急向其他供应商/其他食材发起的替代采购。
 */
@Entity
@Table(name = "substitute_purchases")
@Getter
@Setter
@NoArgsConstructor
public class SubstitutePurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String purchaseNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "cold_chain_case_id")
    private ColdChainCase coldChainCase;

    /** 原供应商（冷链超标责任方，替代采购差价可向其追偿） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** 缺货所需食材 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    /** 受影响餐次 */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "meal_id")
    private MenuMeal meal;

    /** 需求数量（扣除现有可用库存后） */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal requiredQty;

    /** 紧急替代采购的食材（为空表示采购同种食材） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "substitute_ingredient_id")
    private Ingredient substituteIngredient;

    /** 紧急替代采购的供应商（为空表示仍由原供应商补货） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "substitute_supplier_id")
    private Supplier substituteSupplier;

    /** 预估替代采购金额 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal estimatedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubstitutePurchaseStatus status = SubstitutePurchaseStatus.PLACED;

    @Column(length = 512)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
