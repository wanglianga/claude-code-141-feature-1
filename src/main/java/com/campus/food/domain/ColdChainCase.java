package com.campus.food.domain;

import com.campus.food.domain.enums.ColdChainDisposition;
import com.campus.food.domain.enums.ColdChainReviewStatus;
import com.campus.food.domain.enums.IngredientCategory;
import com.campus.food.domain.enums.InventoryUsageScope;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷链温度超标验收处置单。
 * 验收员在冷链食材到货温度高于合同要求时登记到货温度、运输时长、产品批次与抽检照片，
 * 由食堂选择拒收 / 降级为非学生餐使用 / 等待复检，并联动索赔、替代采购、菜单调整与入库用途限制。
 */
@Entity
@Table(name = "cold_chain_cases")
@Getter
@Setter
@NoArgsConstructor
public class ColdChainCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String caseNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    /** 温度超标的送货明细（酸奶等冷链食材行） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "delivery_item_id")
    private DeliveryItem deliveryItem;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "inspector_id")
    private AppUser inspector;

    /** 到货实测车厢温度（℃） */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal arrivalTemperature;

    /** 合同/食材要求的冷链温度上限（℃） */
    @Column(precision = 5, scale = 2)
    private BigDecimal requiredMaxTemp;

    /** 运输时长（分钟） */
    @Column(nullable = false)
    private Integer transportMinutes;

    /** 产品生产批次号（验收员核对登记） */
    @Column(length = 64)
    private String productBatchNo;

    /** 超温货值（数量×合同价，索赔/扣罚基数） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal goodsValue = BigDecimal.ZERO;

    /** 涉及品类（冗余，便于复盘） */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IngredientCategory category;

    @Column(length = 512)
    private String notes;

    // ---- 食堂处置决策 ----

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ColdChainDisposition disposition = ColdChainDisposition.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ColdChainReviewStatus reviewStatus = ColdChainReviewStatus.PENDING_REVIEW;

    /** 降级使用范围：教职工餐 / 非即食加工 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private InventoryUsageScope usageScope;

    /** 处置/决策人（食堂） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;

    private LocalDateTime decidedAt;

    // ---- 质检复查 / 复检 ----

    /** 质检复查人 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    private LocalDateTime reviewedAt;

    /** 质检复查 / 复检意见 */
    @Column(length = 512)
    private String reviewNote;

    /** 实验室复检报告编号 */
    @Column(length = 64)
    private String labReportNo;

    // ---- 联动单据 ----

    /** 拒收/复检不合格生成的退货单 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "return_order_id")
    private ReturnOrder returnOrder;

    /** 温度超标质量索赔单（与供应商结算直接关联） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id")
    private Claim claim;

    /** 冷链温度超标异常记录 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "anomaly_id")
    private AnomalyRecord anomaly;

    /** 降级复查放行时确认的折价率（0.2 表示按 8 折结算），结算时扣减 */
    @Column(precision = 5, scale = 4)
    private BigDecimal discountRate;

    /** 降级复查放行后的验收入库数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal releasedQty = BigDecimal.ZERO;

    /** 降级放行后的限制用途库存（仅教职工餐/非即食加工） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "restricted_stock_id")
    private RestrictedStock restrictedStock;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
