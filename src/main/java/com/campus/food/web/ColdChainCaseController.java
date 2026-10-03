package com.campus.food.web;

import com.campus.food.domain.MenuAdjustment;
import com.campus.food.domain.SubstitutePurchase;
import com.campus.food.domain.enums.ColdChainCaseStatus;
import com.campus.food.domain.enums.ColdChainDisposition;
import com.campus.food.domain.enums.DowngradeUsageScope;
import com.campus.food.domain.enums.RetestOutcome;
import com.campus.food.service.ColdChainCaseService;
import com.campus.food.domain.ColdChainCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Tag(name = "冷链温度超标处置")
@RestController
@RequestMapping("/api/cold-chain-cases")
@RequiredArgsConstructor
public class ColdChainCaseController {

    private final ColdChainCaseService coldChainCaseService;

    public record DecideReq(@NotNull ColdChainDisposition disposition,
                            DowngradeUsageScope usageScope, String note) {
    }

    public record DowngradeApprovalReq(@NotNull Boolean approve, BigDecimal discountRate, String note) {
    }

    public record QualityReviewReq(@NotNull Boolean passed, String note) {
    }

    public record RetestReq(@NotNull RetestOutcome outcome, BigDecimal retestTemp, String note) {
    }

    public record ConfirmAdjustmentReq(String note) {
    }

    @Operation(summary = "冷链处置案件列表（可按供应商/状态过滤）")
    @GetMapping
    public List<ColdChainCase> list(@RequestParam(required = false) Long supplierId,
                                    @RequestParam(required = false) ColdChainCaseStatus status) {
        return coldChainCaseService.list(supplierId, status);
    }

    @Operation(summary = "冷链处置案件详情（含替代采购单与菜单调整记录）")
    @GetMapping("/{id}")
    public ColdChainCaseService.CaseDetail detail(@PathVariable Long id) {
        return coldChainCaseService.detail(id);
    }

    @Operation(summary = "食堂处置决定：REJECT 拒收 / DOWNGRADE 降级为非学生餐使用（须指定 usageScope）/ RETEST 等待复检")
    @PostMapping("/{id}/decide")
    @PreAuthorize("hasRole('CANTEEN')")
    public ColdChainCase decide(Authentication auth, @PathVariable Long id,
                                @Valid @RequestBody DecideReq req) {
        return coldChainCaseService.decide(id, auth.getName(),
                new ColdChainCaseService.DecideReq(req.disposition(), req.usageScope(), req.note()));
    }

    @Operation(summary = "采购审批降级折价（驳回则转拒收退货并索赔）")
    @PostMapping("/{id}/downgrade-approval")
    @PreAuthorize("hasRole('PROCUREMENT')")
    public ColdChainCase downgradeApproval(Authentication auth, @PathVariable Long id,
                                           @Valid @RequestBody DowngradeApprovalReq req) {
        return coldChainCaseService.approveDowngrade(id, auth.getName(),
                req.approve(), req.discountRate(), req.note());
    }

    @Operation(summary = "质检复查（降级批次入库前强制闸门：通过则限定用途入库，不通过转拒收）")
    @PostMapping("/{id}/quality-review")
    @PreAuthorize("hasRole('QUALITY')")
    public ColdChainCase qualityReview(Authentication auth, @PathVariable Long id,
                                       @Valid @RequestBody QualityReviewReq req) {
        return coldChainCaseService.qualityReview(id, auth.getName(), req.passed(), req.note());
    }

    @Operation(summary = "质检复检（等待复检的案件：合格正常入库并免除冷链扣罚，不合格退回食堂重新处置）")
    @PostMapping("/{id}/retest")
    @PreAuthorize("hasRole('QUALITY')")
    public ColdChainCase retest(Authentication auth, @PathVariable Long id,
                                @Valid @RequestBody RetestReq req) {
        return coldChainCaseService.retest(id, auth.getName(), req.outcome(), req.retestTemp(), req.note());
    }

    @Operation(summary = "食堂确认菜单调整（食材替代确认后同步更新餐次食材用量）")
    @PostMapping("/menu-adjustments/{adjustmentId}/confirm")
    @PreAuthorize("hasRole('CANTEEN')")
    public MenuAdjustment confirmAdjustment(Authentication auth, @PathVariable Long adjustmentId,
                                            @RequestBody(required = false) ConfirmAdjustmentReq req) {
        return coldChainCaseService.confirmMenuAdjustment(adjustmentId, auth.getName(),
                req == null ? null : req.note());
    }

    @Operation(summary = "替代采购单列表")
    @GetMapping("/substitute-purchases")
    public List<SubstitutePurchase> listSubstitutes(@RequestParam(required = false) Long supplierId) {
        return coldChainCaseService.listSubstitutes(supplierId);
    }

    @Operation(summary = "替代采购到货入库")
    @PostMapping("/substitute-purchases/{id}/receive")
    @PreAuthorize("hasAnyRole('PROCUREMENT','CANTEEN')")
    public SubstitutePurchase receiveSubstitute(@PathVariable Long id) {
        return coldChainCaseService.receiveSubstitute(id);
    }
}
