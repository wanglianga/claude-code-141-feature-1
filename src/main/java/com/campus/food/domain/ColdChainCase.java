package com.campus.food.domain;

import com.campus.food.domain.enums.ColdChainCaseStatus;
import com.campus.food.domain.enums.ColdChainDisposition;
import com.campus.food.domain.enums.DowngradeUsageScope;
import com.campus.food.domain.enums.RetestOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷链温度超标处置案件：一个验收明细（一批问题食材）一个案件。
 * 录入：到货温度、运输时长、产品批次、抽检照片；
 * 处置：食堂选择拒收 / 降级为非学生餐使用 / 等待复检；
 * 拒收联动退货、索赔、替代采购与菜单调整；降级限定用途并经采购折价审批 + 质检复查后入库。
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
    @JoinColumn(name = "record_id")
    private AcceptanceRecord record;

    @OneToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "acceptance_item_id", unique = true)
    private AcceptanceItem acceptanceItem;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private DeliveryBatch batch;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    /** 暂扣数量（整批问题食材） */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** 合同单价快照（索赔/折价依据） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    /** 合同要求温度区间快照 */
    @Column(precision = 5, scale = 2)
    private BigDecimal contractMinTemp;

    @Column(precision = 5, scale = 2)
    private BigDecimal contractMaxTemp;

    /** 到货实测温度（℃） */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal arrivalTemp;

    /** 运输时长（分钟） */
    private Integer transportDurationMinutes;

    /** 产品批次号 */
    @Column(length = 64)
    private String productionBatchNo;

    /** 抽检照片 URL（逗号分隔多张） */
    @Column(length = 1024)
    private String photosUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ColdChainCaseStatus status = ColdChainCaseStatus.ON_HOLD;

    /** 食堂选择的处置方式 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ColdChainDisposition disposition;

    /** 降级使用的限定用途 */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private DowngradeUsageScope usageScope;

    /** 降级折价率（0.2 表示按 8 折结算） */
    @Column(precision = 5, scale = 4)
    private BigDecimal discountRate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;

    private LocalDateTime decidedAt;

    /** 采购降级折价审批人 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "downgrade_approved_by")
    private AppUser downgradeApprovedBy;

    private LocalDateTime downgradeApprovedAt;

    /** 复检人 / 结论 / 复测温度 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "retest_by")
    private AppUser retestBy;

    private LocalDateTime retestAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RetestOutcome retestOutcome;

    @Column(precision = 5, scale = 2)
    private BigDecimal retestTemp;

    @Column(length = 512)
    private String retestNote;

    /** 质检复查人（降级批次入库前复查） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "quality_review_by")
    private AppUser qualityReviewBy;

    private LocalDateTime qualityReviewAt;

    @Column(length = 512)
    private String qualityReviewNote;

    /** 本案件产生的冷链异常（扣罚依据） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "anomaly_id")
    private AnomalyRecord anomaly;

    /** 拒收/降级驳回产生的索赔 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id")
    private Claim claim;

    @Column(length = 512)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime closedAt;
}
