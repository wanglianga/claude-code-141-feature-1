package com.campus.food.web;

import com.campus.food.domain.InventoryTxn;
import com.campus.food.domain.RestrictedStockBatch;
import com.campus.food.domain.StockLevel;
import com.campus.food.domain.enums.ConsumePurpose;
import com.campus.food.domain.enums.InventoryTxnType;
import com.campus.food.repo.RestrictedStockBatchRepo;
import com.campus.food.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "库存")
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final RestrictedStockBatchRepo restrictedBatchRepo;

    public record AdjustReq(@NotNull Long ingredientId, @NotNull BigDecimal qty,
                            @NotNull InventoryTxnType type, String remark) {
    }

    public record ConsumeReq(@NotNull Long ingredientId, @NotNull BigDecimal qty,
                             @NotNull ConsumePurpose purpose, String remark) {
    }

    @Operation(summary = "库存水位列表")
    @GetMapping("/levels")
    public List<StockLevel> levels() {
        return inventoryService.levels();
    }

    @Operation(summary = "安全线预警（库存低于安全线的食材）")
    @GetMapping("/safety-alerts")
    public List<StockLevel> safetyAlerts() {
        return inventoryService.safetyAlerts();
    }

    @Operation(summary = "库存流水")
    @GetMapping("/txns")
    public List<InventoryTxn> txns(@RequestParam Long ingredientId) {
        return inventoryService.txns(ingredientId);
    }

    @Operation(summary = "手工库存调整（食堂领用出库/盘点）")
    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('CANTEEN','PROCUREMENT')")
    public StockLevel adjust(@Valid @RequestBody AdjustReq req) {
        if (req.type() == InventoryTxnType.OUT && req.qty().signum() > 0) {
            // 出库传正数，内部转为负
            return inventoryService.change(req.ingredientId(), req.qty().negate(), req.type(),
                    "MANUAL", null, req.remark());
        }
        return inventoryService.change(req.ingredientId(), req.qty(), req.type(),
                "MANUAL", null, req.remark());
    }

    @Operation(summary = "按用途领用（学生餐禁止使用冷链降级受限批次；教职工餐/非即食加工优先消耗匹配的降级批次）")
    @PostMapping("/consume")
    @PreAuthorize("hasRole('CANTEEN')")
    public StockLevel consume(@Valid @RequestBody ConsumeReq req) {
        return inventoryService.consumeFor(req.ingredientId(), req.qty(), req.purpose(), req.remark());
    }

    @Operation(summary = "冷链降级受限库存批次（限教职工餐/非即食加工使用）")
    @GetMapping("/restricted-batches")
    public List<RestrictedStockBatch> restrictedBatches(@RequestParam(required = false) Long ingredientId) {
        return ingredientId != null ? restrictedBatchRepo.findByIngredientId(ingredientId)
                : restrictedBatchRepo.findAll();
    }
}
