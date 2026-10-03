package com.campus.food.web;

import com.campus.food.domain.InventoryTxn;
import com.campus.food.domain.StockLevel;
import com.campus.food.domain.enums.InventoryTxnType;
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

    public record AdjustReq(@NotNull Long ingredientId, @NotNull BigDecimal qty,
                            @NotNull InventoryTxnType type, String remark) {
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

    @Operation(summary = "限制用途库存（冷链降级批次，仅限教职工餐/非即食加工，不计入学生餐可用库存）")
    @GetMapping("/restricted-levels")
    public List<com.campus.food.domain.RestrictedStock> restrictedLevels(@RequestParam(required = false) Long ingredientId) {
        return inventoryService.restrictedLevels(ingredientId);
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
}
