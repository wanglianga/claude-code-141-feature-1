package com.campus.food.domain;

import com.campus.food.domain.enums.SubstitutePurchaseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 替代采购单：冷链超标批次被拒收且影响当天菜单时，
 * 系统同步生成紧急替代采购记录（向备选供应商采购同品类/替代食材）。
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

    /** 备选供应商（优先同品类非涉事供应商） */
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** 采购估算单价（参考价） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubstitutePurchaseStatus status = SubstitutePurchaseStatus.PLACED;

    @Column(length = 512)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime receivedAt;
}
