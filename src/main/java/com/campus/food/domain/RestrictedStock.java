package com.campus.food.domain;

import com.campus.food.domain.enums.InventoryUsageScope;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 限制用途库存：冷链超标降级、经质检复查放行入库的批次单独存放，
 * 只能进入教职工餐或非即食加工，不进入学生餐可用库存。
 */
@Entity
@Table(name = "restricted_stocks")
@Getter
@Setter
@NoArgsConstructor
public class RestrictedStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "cold_chain_case_id")
    private ColdChainCase coldChainCase;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InventoryUsageScope usageScope;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    /** 已领用数量 */
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal consumedQty = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
