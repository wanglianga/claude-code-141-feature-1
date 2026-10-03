package com.campus.food.domain;

import com.campus.food.domain.enums.DowngradeUsageScope;
import com.campus.food.domain.enums.RestrictedBatchStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 受限库存批次：冷链超标后降级使用、经采购折价审批与质检复查通过才入库的批次。
 * 该批食材只能按 {@link #usageScope} 限定用途（教职工餐 / 非即食加工）领用，
 * 领用时服务强制校验用途，禁止进入学生餐即食链路。
 */
@Entity
@Table(name = "restricted_stock_batches")
@Getter
@Setter
@NoArgsConstructor
public class RestrictedStockBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "cold_chain_case_id", unique = true)
    private ColdChainCase coldChainCase;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    /** 入库总量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** 尚可领用数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal remainingQty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DowngradeUsageScope usageScope;

    /** 折价率（结算用，冗余自案件） */
    @Column(precision = 5, scale = 4)
    private BigDecimal discountRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RestrictedBatchStatus status = RestrictedBatchStatus.AVAILABLE;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
