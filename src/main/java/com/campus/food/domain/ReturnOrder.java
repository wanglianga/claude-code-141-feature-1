package com.campus.food.domain;

import com.campus.food.domain.enums.ReturnStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退货单 */
@Entity
@Table(name = "return_orders")
@Getter
@Setter
@NoArgsConstructor
public class ReturnOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String returnNo;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** 关联验收明细（冷链超标在验收环节直接拒收时可为空） */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "acceptance_item_id")
    private AcceptanceItem acceptanceItem;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "ingredient_id")
    private Ingredient ingredient;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(length = 512)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReturnStatus status = ReturnStatus.PENDING;

    /** 应退金额（结算扣减依据） */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime processedAt;
}
