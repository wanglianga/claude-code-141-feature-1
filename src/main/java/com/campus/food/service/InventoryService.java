package com.campus.food.service;

import com.campus.food.domain.Ingredient;
import com.campus.food.domain.InventoryTxn;
import com.campus.food.domain.RestrictedStockBatch;
import com.campus.food.domain.StockLevel;
import com.campus.food.domain.enums.ConsumePurpose;
import com.campus.food.domain.enums.DowngradeUsageScope;
import com.campus.food.domain.enums.InventoryTxnType;
import com.campus.food.domain.enums.RestrictedBatchStatus;
import com.campus.food.repo.IngredientRepo;
import com.campus.food.repo.InventoryTxnRepo;
import com.campus.food.repo.RestrictedStockBatchRepo;
import com.campus.food.repo.StockLevelRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 库存服务：水位维护 + 流水 + 安全线；冷链降级批次按用途管制领用 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockLevelRepo stockLevelRepo;
    private final InventoryTxnRepo txnRepo;
    private final IngredientRepo ingredientRepo;
    private final RestrictedStockBatchRepo restrictedBatchRepo;

    /**
     * 库存变动（qty 正为入库，负为出库），同时记录流水。
     */
    @Transactional
    public StockLevel change(Long ingredientId, BigDecimal qty, InventoryTxnType type,
                             String refType, Long refId, String remark) {
        Ingredient ingredient = ingredientRepo.findById(ingredientId)
                .orElseThrow(() -> BusinessException.notFound("食材不存在: " + ingredientId));
        StockLevel level = stockLevelRepo.findByIngredientId(ingredientId).orElseGet(() -> {
            StockLevel s = new StockLevel();
            s.setIngredient(ingredient);
            s.setQuantity(BigDecimal.ZERO);
            return s;
        });
        BigDecimal newQty = level.getQuantity().add(qty);
        if (newQty.signum() < 0) {
            throw BusinessException.badRequest("库存不足：[" + ingredient.getName() + "] 当前 "
                    + level.getQuantity() + "，需要出库 " + qty.negate());
        }
        level.setQuantity(newQty);
        level.setUpdatedAt(LocalDateTime.now());
        stockLevelRepo.save(level);

        InventoryTxn txn = new InventoryTxn();
        txn.setIngredient(ingredient);
        txn.setChangeQty(qty);
        txn.setBalanceAfter(newQty);
        txn.setType(type);
        txn.setRefType(refType);
        txn.setRefId(refId);
        txn.setRemark(remark);
        txnRepo.save(txn);
        return level;
    }

    public List<StockLevel> levels() {
        return stockLevelRepo.findAll();
    }

    /** 低于安全线的库存 */
    public List<StockLevel> safetyAlerts() {
        return stockLevelRepo.findBelowSafetyLine();
    }

    public List<InventoryTxn> txns(Long ingredientId) {
        return txnRepo.findByIngredientIdOrderByCreatedAtDesc(ingredientId);
    }

    public BigDecimal currentQty(Long ingredientId) {
        return stockLevelRepo.findByIngredientId(ingredientId)
                .map(StockLevel::getQuantity)
                .orElse(BigDecimal.ZERO);
    }

    /** 冷链降级受限在库量（按食材汇总） */
    public BigDecimal restrictedQty(Long ingredientId) {
        return restrictedBatchRepo.findByIngredientIdAndStatus(ingredientId, RestrictedBatchStatus.AVAILABLE)
                .stream().map(RestrictedStockBatch::getRemainingQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 按用途领用出库并强制用途管制：
     * <ul>
     *   <li>学生餐 STUDENT_MEAL：只能使用非受限库存，不足直接拒绝；</li>
     *   <li>教职工餐 / 非即食加工：优先消耗用途匹配的冷链降级受限批次，余量从正常库存出。</li>
     * </ul>
     */
    @Transactional
    public StockLevel consumeFor(Long ingredientId, BigDecimal qty, ConsumePurpose purpose, String remark) {
        if (qty == null || qty.signum() <= 0) {
            throw BusinessException.badRequest("领用数量必须大于 0");
        }
        Ingredient ingredient = ingredientRepo.findById(ingredientId)
                .orElseThrow(() -> BusinessException.notFound("食材不存在: " + ingredientId));
        StockLevel level = stockLevelRepo.findByIngredientId(ingredientId).orElseThrow(() ->
                BusinessException.badRequest("[" + ingredient.getName() + "] 无库存，无法领用"));
        BigDecimal restricted = restrictedQty(ingredientId);
        BigDecimal unrestricted = level.getQuantity().subtract(restricted).max(BigDecimal.ZERO);

        if (purpose == ConsumePurpose.STUDENT_MEAL) {
            if (qty.compareTo(unrestricted) > 0) {
                throw BusinessException.badRequest("[" + ingredient.getName() + "] 学生餐只能使用合格非受限库存，"
                        + "可用 " + unrestricted + ingredient.getUnit() + "，需要 " + qty + ingredient.getUnit()
                        + "（冷链降级批次禁止进入学生餐）");
            }
            return applyOut(ingredient, level, qty, purpose, remark);
        }

        if (level.getQuantity().compareTo(qty) < 0) {
            throw BusinessException.badRequest("库存不足：[" + ingredient.getName() + "] 当前 "
                    + level.getQuantity() + ingredient.getUnit() + "，需要领用 " + qty + ingredient.getUnit());
        }
        // 优先消耗用途匹配的受限批次；先做可行性校验，再统一扣减（异常时不留半截改动）
        DowngradeUsageScope scope = purpose == ConsumePurpose.STAFF_MEAL
                ? DowngradeUsageScope.STAFF_MEAL : DowngradeUsageScope.NON_READY_TO_EAT;
        List<RestrictedStockBatch> matching = restrictedBatchRepo
                .findByIngredientIdAndStatus(ingredientId, RestrictedBatchStatus.AVAILABLE).stream()
                .filter(rb -> rb.getUsageScope() == scope)
                .toList();
        BigDecimal matchingAvailable = matching.stream()
                .map(RestrictedStockBatch::getRemainingQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fromRestricted = matchingAvailable.min(qty);
        BigDecimal fromUnrestricted = qty.subtract(fromRestricted);
        if (fromUnrestricted.compareTo(unrestricted) > 0) {
            throw BusinessException.badRequest("[" + ingredient.getName() + "] 与用途[" + purpose
                    + "]匹配的库存不足：匹配的降级批次 " + matchingAvailable + ingredient.getUnit()
                    + "、合格非受限库存 " + unrestricted + ingredient.getUnit()
                    + "，无法满足领用 " + qty + ingredient.getUnit());
        }
        BigDecimal need = fromRestricted;
        for (RestrictedStockBatch rb : matching) {
            if (need.signum() == 0) {
                break;
            }
            BigDecimal take = rb.getRemainingQty().min(need);
            rb.setRemainingQty(rb.getRemainingQty().subtract(take));
            if (rb.getRemainingQty().signum() == 0) {
                rb.setStatus(RestrictedBatchStatus.USED_UP);
            }
            restrictedBatchRepo.save(rb);
            need = need.subtract(take);
        }
        return applyOut(ingredient, level, qty, purpose, remark);
    }

    private StockLevel applyOut(Ingredient ingredient, StockLevel level, BigDecimal qty,
                                ConsumePurpose purpose, String remark) {
        BigDecimal newQty = level.getQuantity().subtract(qty);
        level.setQuantity(newQty);
        level.setUpdatedAt(LocalDateTime.now());
        stockLevelRepo.save(level);

        InventoryTxn txn = new InventoryTxn();
        txn.setIngredient(ingredient);
        txn.setChangeQty(qty.negate());
        txn.setBalanceAfter(newQty);
        txn.setType(InventoryTxnType.OUT);
        txn.setRefType("CONSUME");
        txn.setRemark((purpose == null ? "领用" : purpose.name() + " 领用")
                + (remark == null || remark.isBlank() ? "" : "：" + remark));
        txnRepo.save(txn);
        return level;
    }
}
