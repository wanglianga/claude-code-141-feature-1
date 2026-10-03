package com.campus.food.service;

import com.campus.food.domain.Ingredient;
import com.campus.food.domain.InventoryTxn;
import com.campus.food.domain.RestrictedStock;
import com.campus.food.domain.StockLevel;
import com.campus.food.domain.enums.InventoryTxnType;
import com.campus.food.repo.IngredientRepo;
import com.campus.food.repo.InventoryTxnRepo;
import com.campus.food.repo.RestrictedStockRepo;
import com.campus.food.repo.StockLevelRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 库存服务：水位维护 + 流水 + 安全线 + 冷链降级限制用途库存 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockLevelRepo stockLevelRepo;
    private final InventoryTxnRepo txnRepo;
    private final IngredientRepo ingredientRepo;
    private final RestrictedStockRepo restrictedRepo;

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

    /** 冷链降级复查放行：计入限制用途库存流水（不增加学生餐可用的正常库存） */
    @Transactional
    public void restrictedIn(com.campus.food.domain.Ingredient ingredient, BigDecimal qty,
                             Long coldChainCaseId, String remark) {
        InventoryTxn txn = new InventoryTxn();
        txn.setIngredient(ingredient);
        txn.setChangeQty(qty);
        txn.setBalanceAfter(qty);
        txn.setType(InventoryTxnType.RESTRICTED_IN);
        txn.setRefType("COLD_CHAIN");
        txn.setRefId(coldChainCaseId);
        txn.setRemark(remark);
        txnRepo.save(txn);
    }

    /** 限制用途库存领用出库（教职工餐/非即食加工） */
    @Transactional
    public void restrictedOut(com.campus.food.domain.Ingredient ingredient, BigDecimal qty,
                              Long coldChainCaseId, String remark) {
        BigDecimal left = restrictedRepo.findByColdChainCaseId(coldChainCaseId)
                .map(rs -> rs.getQuantity().subtract(rs.getConsumedQty()))
                .orElse(BigDecimal.ZERO);
        InventoryTxn txn = new InventoryTxn();
        txn.setIngredient(ingredient);
        txn.setChangeQty(qty.negate());
        txn.setBalanceAfter(left);
        txn.setType(InventoryTxnType.RESTRICTED_OUT);
        txn.setRefType("COLD_CHAIN");
        txn.setRefId(coldChainCaseId);
        txn.setRemark(remark);
        txnRepo.save(txn);
    }

    /** 限制用途库存（冷链降级，仅教职工餐/非即食加工） */
    public List<RestrictedStock> restrictedLevels(Long ingredientId) {
        return ingredientId == null ? restrictedRepo.findAll() : restrictedRepo.findByIngredientId(ingredientId);
    }

    public BigDecimal currentQty(Long ingredientId) {
        return stockLevelRepo.findByIngredientId(ingredientId)
                .map(StockLevel::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
