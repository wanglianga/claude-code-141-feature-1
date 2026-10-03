package com.campus.food.web;

import com.campus.food.domain.ColdChainCase;
import com.campus.food.domain.MenuAdjustment;
import com.campus.food.domain.RestrictedStock;
import com.campus.food.domain.SubstitutePurchase;
import com.campus.food.domain.enums.ColdChainDisposition;
import com.campus.food.domain.enums.ColdChainReviewStatus;
import com.campus.food.domain.enums.InventoryUsageScope;
import com.campus.food.service.ColdChainCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "冷链温度超标验收")
@RestController
@RequestMapping("/api/cold-chain")
@RequiredArgsConstructor
public class ColdChainController {

    private final ColdChainCaseService coldChainService;

    public record PhotoReq(@NotNull String photoUrl, String caption) {
    }

    public record RegisterReq(@NotNull Long batchId, @NotNull Long deliveryItemId,
                              @NotNull BigDecimal arrivalTemperature, @NotNull Integer transportMinutes,
                              String productBatchNo, String notes,
                              @NotEmpty List<PhotoReq> photos) {
    }

    public record DecisionReq(@NotNull ColdChainDisposition disposition, InventoryUsageScope usageScope,
                              BigDecimal claimAmount, String note) {
    }

    public record QcReviewReq(@NotNull Boolean pass, BigDecimal discountRate, String note) {
    }

    public record LabResultReq(@NotNull Boolean pass, String labReportNo, String note) {
    }

    public record ConsumeReq(@NotNull BigDecimal qty, @NotNull String purpose) {
    }

    @Operation(summary = "验收员登记冷链温度超标（到货温度、运输时长、产品批次、抽检照片）")
    @PostMapping("/cases")
    @PreAuthorize("hasRole('INSPECTOR')")
    public ColdChainCase register(Authentication auth, @Valid @RequestBody RegisterReq req) {
        List<ColdChainCaseService.PhotoInput> photos = req.photos().stream()
                .map(p -> new ColdChainCaseService.PhotoInput(p.photoUrl(), p.caption()))
                .toList();
        return coldChainService.register(auth.getName(), new ColdChainCaseService.RegisterInput(
                req.batchId(), req.deliveryItemId(), req.arrivalTemperature(), req.transportMinutes(),
                req.productBatchNo(), req.notes(), photos));
    }

    @Operation(summary = "冷链超标处置决策：拒收 / 降级为非学生餐使用 / 等待复检")
    @PostMapping("/cases/{id}/decision")
    @PreAuthorize("hasRole('CANTEEN')")
    public ColdChainCase decide(Authentication auth, @PathVariable Long id,
                                @Valid @RequestBody DecisionReq req) {
        return coldChainService.decide(auth.getName(), id, req.disposition(), req.usageScope(),
                req.claimAmount(), req.note());
    }

    @Operation(summary = "质检人员复查降级批次：合格入限制用途库存（教职工餐/非即食加工），不合格转拒收")
    @PostMapping("/cases/{id}/qc-review")
    @PreAuthorize("hasRole('QUALITY')")
    public ColdChainCase qcReview(Authentication auth, @PathVariable Long id,
                                  @Valid @RequestBody QcReviewReq req) {
        return coldChainService.qcReview(auth.getName(), id, req.pass(), req.discountRate(), req.note());
    }

    @Operation(summary = "等待复检批次录入实验室复检结果：合格正常入库，不合格转拒收")
    @PostMapping("/cases/{id}/lab-result")
    @PreAuthorize("hasRole('QUALITY')")
    public ColdChainCase labResult(Authentication auth, @PathVariable Long id,
                                   @Valid @RequestBody LabResultReq req) {
        return coldChainService.labResult(auth.getName(), id, req.pass(), req.labReportNo(), req.note());
    }

    @Operation(summary = "限制用途库存领用（仅教职工餐/非即食加工，不能进入学生餐）")
    @PostMapping("/cases/{id}/restricted-consume")
    @PreAuthorize("hasRole('CANTEEN')")
    public RestrictedStock consume(@PathVariable Long id, @Valid @RequestBody ConsumeReq req) {
        return coldChainService.consumeRestricted(id, req.qty(), req.purpose());
    }

    @Operation(summary = "冷链超标处置单列表（可按处置方式/复查状态/批次/供应商过滤）")
    @GetMapping("/cases")
    public List<ColdChainCase> list(@RequestParam(required = false) ColdChainDisposition disposition,
                                    @RequestParam(required = false) ColdChainReviewStatus reviewStatus,
                                    @RequestParam(required = false) Long batchId,
                                    @RequestParam(required = false) Long supplierId) {
        return coldChainService.list(disposition, reviewStatus, batchId, supplierId);
    }

    @Operation(summary = "冷链超标处置单详情（含抽检照片、替代采购、菜单调整）")
    @GetMapping("/cases/{id}")
    public ColdChainCaseService.CaseDetail detail(@PathVariable Long id) {
        return coldChainService.detail(id);
    }

    @Operation(summary = "处置单的替代采购记录")
    @GetMapping("/cases/{id}/substitutes")
    public List<SubstitutePurchase> substitutes(@PathVariable Long id) {
        return coldChainService.substitutesOfCase(id);
    }

    @Operation(summary = "处置单的菜单调整记录")
    @GetMapping("/cases/{id}/menu-adjustments")
    public List<MenuAdjustment> menuAdjustments(@PathVariable Long id) {
        return coldChainService.adjustmentsOfCase(id);
    }

    @Operation(summary = "替代采购登记到货")
    @PostMapping("/substitutes/{id}/receive")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public SubstitutePurchase receiveSubstitute(@PathVariable Long id) {
        return coldChainService.receiveSubstitute(id);
    }

    @Operation(summary = "限制用途库存列表（冷链降级，仅教职工餐/非即食加工）")
    @GetMapping("/restricted-stocks")
    public List<RestrictedStock> restrictedStocks(@RequestParam(required = false) Long ingredientId) {
        return coldChainService.restrictedStocks(ingredientId);
    }
}
