package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 冷链温度超标验收处置服务。
 *
 * 验收员登记到货温度、运输时长、产品批次与抽检照片；食堂选择：
 * 1) 拒收：生成退货单与冷链质量索赔（结算直接扣减），若拒收影响当周菜单，
 *    同步生成替代采购记录与菜单调整记录；
 * 2) 降级为非学生餐使用：限制该批食材只能进入教职工餐或非即食加工，
 *    经质检人员复查合格后才入库（独立的限制用途库存，不进入学生餐可用库存）；
 * 3) 等待复检：取样送检，复检合格正常入库，不合格转拒收流程。
 */
@Service
@RequiredArgsConstructor
public class ColdChainCaseService {

    /** 拒收时自动生成质量索赔的货值比例（合同冷链扣罚另按异常扣罚规则计） */
    private static final BigDecimal REJECT_CLAIM_RATE = new BigDecimal("0.10");
    /** 降级默认折价率 */
    private static final BigDecimal DEFAULT_DISCOUNT_RATE = new BigDecimal("0.20");

    private final ColdChainCaseRepo caseRepo;
    private final ColdChainPhotoRepo photoRepo;
    private final SubstitutePurchaseRepo substituteRepo;
    private final MenuAdjustmentRepo adjustmentRepo;
    private final RestrictedStockRepo restrictedRepo;
    private final DeliveryBatchRepo batchRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final AppUserRepo userRepo;
    private final SupplierRepo supplierRepo;
    private final IngredientRepo ingredientRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final InventoryService inventoryService;
    private final AnomalyService anomalyService;
    private final ClaimService claimService;
    private final ReturnService returnService;

    public record PhotoInput(String photoUrl, String caption) {
    }

    public record RegisterInput(Long batchId, Long deliveryItemId, BigDecimal arrivalTemperature,
                                Integer transportMinutes, String productBatchNo, String notes,
                                List<PhotoInput> photos) {
    }

    /** 验收员登记冷链温度超标取证信息 */
    @Transactional
    public ColdChainCase register(String inspectorUsername, RegisterInput in) {
        DeliveryBatch batch = batchRepo.findById(in.batchId())
                .orElseThrow(() -> BusinessException.notFound("送货批次不存在: " + in.batchId()));
        DeliveryItem di = deliveryItemRepo.findById(in.deliveryItemId())
                .orElseThrow(() -> BusinessException.notFound("送货明细不存在: " + in.deliveryItemId()));
        if (!di.getBatch().getId().equals(batch.getId())) {
            throw BusinessException.badRequest("送货明细不属于该批次: " + in.deliveryItemId());
        }
        if (caseRepo.existsByDeliveryItemId(di.getId())) {
            throw BusinessException.conflict("该送货明细已登记冷链温度超标处置单，不能重复登记");
        }
        Ingredient ingredient = di.getIngredient();
        if (!ingredient.isRequiresColdChain()) {
            throw BusinessException.badRequest("[" + ingredient.getName() + "] 非冷链食材，不适用冷链温度超标验收");
        }
        if (in.arrivalTemperature() == null) {
            throw BusinessException.badRequest("必须登记到货实测温度");
        }
        if (in.transportMinutes() == null || in.transportMinutes() < 0) {
            throw BusinessException.badRequest("必须登记运输时长（分钟）");
        }
        BigDecimal requiredMax = ingredient.getColdChainMaxTemp();
        if (requiredMax != null && in.arrivalTemperature().compareTo(requiredMax) <= 0) {
            throw BusinessException.badRequest("到货温度 " + in.arrivalTemperature()
                    + "℃ 未超出合同要求上限 " + requiredMax + "℃，不能登记冷链超标");
        }
        if (in.photos() == null || in.photos().isEmpty()) {
            throw BusinessException.badRequest("必须上传至少一张抽检照片");
        }
        AppUser inspector = userRepo.findByUsername(inspectorUsername)
                .orElseThrow(() -> BusinessException.notFound("验收员不存在: " + inspectorUsername));
        Supplier supplier = batch.getOrder().getSupplier();
        BigDecimal price = unitPrice(di);
        BigDecimal goodsValue = di.getDeliveredQty().multiply(price).setScale(2, RoundingMode.HALF_UP);

        // 送货登记阶段可能已就该批次登记冷链超标异常，验收确认时复用，避免重复扣罚
        AnomalyRecord anomaly = anomalyRepo.findByBatchId(batch.getId()).stream()
                .filter(a -> a.getType() == AnomalyType.COLD_CHAIN_BREACH && a.getStatus() != AnomalyStatus.RESOLVED)
                .findFirst()
                .orElseGet(() -> anomalyService.create(supplier, batch.getOrder(), batch,
                        AnomalyType.COLD_CHAIN_BREACH, ingredient.getCategory(),
                        "验收确认冷链超标：[" + ingredient.getName() + "] 到货实测 "
                                + in.arrivalTemperature() + "℃（合同要求不高于 " + requiredMax
                                + "℃），运输时长 " + in.transportMinutes() + " 分钟，产品批次 "
                                + in.productBatchNo(),
                        goodsValue));

        ColdChainCase c = new ColdChainCase();
        c.setCaseNo(NoGen.next("CC"));
        c.setBatch(batch);
        c.setDeliveryItem(di);
        c.setIngredient(ingredient);
        c.setInspector(inspector);
        c.setArrivalTemperature(in.arrivalTemperature());
        c.setRequiredMaxTemp(requiredMax);
        c.setTransportMinutes(in.transportMinutes());
        c.setProductBatchNo(in.productBatchNo());
        c.setGoodsValue(goodsValue);
        c.setCategory(ingredient.getCategory());
        c.setNotes(in.notes());
        c.setAnomaly(anomaly);
        caseRepo.save(c);

        for (PhotoInput p : in.photos()) {
            if (p.photoUrl() == null || p.photoUrl().isBlank()) {
                throw BusinessException.badRequest("抽检照片地址不能为空");
            }
            ColdChainPhoto photo = new ColdChainPhoto();
            photo.setColdChainCase(c);
            photo.setPhotoUrl(p.photoUrl());
            photo.setCaption(p.caption());
            photoRepo.save(photo);
        }
        return c;
    }

    /** 食堂处置决策：拒收 / 降级非学生餐 / 等待复检 */
    @Transactional
    public ColdChainCase decide(String canteenUsername, Long caseId,
                                ColdChainDisposition disposition, InventoryUsageScope usageScope,
                                BigDecimal claimAmount, String note) {
        ColdChainCase c = get(caseId);
        if (c.getDisposition() != ColdChainDisposition.PENDING) {
            throw BusinessException.conflict("该冷链超标处置单已做出决策，不能重复决策");
        }
        AppUser canteen = userRepo.findByUsername(canteenUsername)
                .orElseThrow(() -> BusinessException.notFound("食堂用户不存在: " + canteenUsername));
        c.setDecidedBy(canteen);
        c.setDecidedAt(LocalDateTime.now());
        c.setReviewNote(note);

        switch (disposition) {
            case REJECTION -> {
                c.setDisposition(ColdChainDisposition.REJECTION);
                caseRepo.save(c);
                executeRejection(c, canteen, claimAmount);
            }
            case DOWNGRADE -> {
                if (usageScope != InventoryUsageScope.STAFF_MEAL
                        && usageScope != InventoryUsageScope.COOKED_ONLY) {
                    throw BusinessException.badRequest("降级使用必须指定用途：教职工餐(STAFF_MEAL)或非即食加工(COOKED_ONLY)");
                }
                c.setDisposition(ColdChainDisposition.DOWNGRADE);
                c.setUsageScope(usageScope);
                c.setReviewStatus(ColdChainReviewStatus.PENDING_REVIEW);
                caseRepo.save(c);
            }
            case RECHECK -> {
                c.setDisposition(ColdChainDisposition.RECHECK);
                c.setReviewStatus(ColdChainReviewStatus.PENDING_LAB);
                caseRepo.save(c);
            }
            default -> throw BusinessException.badRequest("不支持的处置方式: " + disposition);
        }
        return c;
    }

    /** 质检人员对降级批次复查：合格则入限制用途库存，不合格转拒收 */
    @Transactional
    public ColdChainCase qcReview(String qualityUsername, Long caseId, boolean pass,
                                  BigDecimal discountRate, String note) {
        ColdChainCase c = get(caseId);
        if (c.getDisposition() != ColdChainDisposition.DOWNGRADE
                || c.getReviewStatus() != ColdChainReviewStatus.PENDING_REVIEW) {
            throw BusinessException.conflict("该处置单当前状态不允许质检复查");
        }
        AppUser quality = userRepo.findByUsername(qualityUsername)
                .orElseThrow(() -> BusinessException.notFound("质检人员不存在: " + qualityUsername));
        c.setReviewedBy(quality);
        c.setReviewedAt(LocalDateTime.now());
        c.setReviewNote(note);
        if (pass) {
            BigDecimal rate = discountRate == null ? DEFAULT_DISCOUNT_RATE : discountRate;
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw BusinessException.badRequest("折价率必须在 0~1 之间");
            }
            c.setReviewStatus(ColdChainReviewStatus.PASSED);
            c.setDiscountRate(rate);
            BigDecimal qty = c.getDeliveryItem().getDeliveredQty();
            c.setReleasedQty(qty);
            caseRepo.save(c);

            RestrictedStock rs = new RestrictedStock();
            rs.setColdChainCase(c);
            rs.setIngredient(c.getIngredient());
            rs.setUsageScope(c.getUsageScope());
            rs.setQuantity(qty);
            restrictedRepo.save(rs);
            c.setRestrictedStock(rs);
            caseRepo.save(c);

            inventoryService.restrictedIn(c.getIngredient(), qty, c.getId(),
                    "冷链超标降级入库（" + scopeLabel(c.getUsageScope()) + "，复查人 " + quality.getFullName() + "）");
        } else {
            c.setReviewStatus(ColdChainReviewStatus.FAILED);
            caseRepo.save(c);
            executeRejection(c, quality, null);
        }
        return c;
    }

    /** 等待复检批次的实验室复检结果：合格正常入库，不合格转拒收 */
    @Transactional
    public ColdChainCase labResult(String qualityUsername, Long caseId, boolean pass,
                                   String labReportNo, String note) {
        ColdChainCase c = get(caseId);
        if (c.getDisposition() != ColdChainDisposition.RECHECK
                || c.getReviewStatus() != ColdChainReviewStatus.PENDING_LAB) {
            throw BusinessException.conflict("该处置单当前状态不等待复检结果");
        }
        AppUser quality = userRepo.findByUsername(qualityUsername)
                .orElseThrow(() -> BusinessException.notFound("质检人员不存在: " + qualityUsername));
        c.setReviewedBy(quality);
        c.setReviewedAt(LocalDateTime.now());
        c.setLabReportNo(labReportNo);
        c.setReviewNote(note);
        if (pass) {
            c.setReviewStatus(ColdChainReviewStatus.PASSED);
            BigDecimal qty = c.getDeliveryItem().getDeliveredQty();
            c.setReleasedQty(qty);
            caseRepo.save(c);
            // 复检合格：正常入库，可用于学生餐
            inventoryService.change(c.getIngredient().getId(), qty, InventoryTxnType.IN,
                    "COLD_CHAIN", c.getId(), "冷链复检合格入库 " + c.getCaseNo()
                            + (labReportNo == null ? "" : "（报告 " + labReportNo + "）"));
        } else {
            c.setReviewStatus(ColdChainReviewStatus.FAILED);
            caseRepo.save(c);
            executeRejection(c, quality, null);
        }
        return c;
    }

    /** 限制用途库存领用（教职工餐/非即食加工），不允许进入学生餐 */
    @Transactional
    public RestrictedStock consumeRestricted(Long caseId, BigDecimal qty, String purpose) {
        ColdChainCase c = get(caseId);
        RestrictedStock rs = c.getRestrictedStock();
        if (rs == null) {
            throw BusinessException.conflict("该处置单没有经复查放行的限制用途库存");
        }
        BigDecimal available = rs.getQuantity().subtract(rs.getConsumedQty());
        if (qty == null || qty.signum() <= 0) {
            throw BusinessException.badRequest("领用数量必须大于 0");
        }
        if (qty.compareTo(available) > 0) {
            throw BusinessException.badRequest("限制用途库存不足：可用 " + available + "，申请领用 " + qty);
        }
        rs.setConsumedQty(rs.getConsumedQty().add(qty));
        rs.setUpdatedAt(LocalDateTime.now());
        restrictedRepo.save(rs);
        inventoryService.restrictedOut(c.getIngredient(), qty, c.getId(),
                "限制用途领用（" + scopeLabel(rs.getUsageScope()) + "）：" + purpose);
        return rs;
    }

    /**
     * 拒收执行：退货单 + 冷链质量索赔；若拒收导致当周在排/已备餐餐次缺货，
     * 同步生成替代采购记录与菜单调整记录。
     */
    private void executeRejection(ColdChainCase c, AppUser actor, BigDecimal claimAmountOverride) {
        DeliveryBatch batch = c.getBatch();
        Supplier supplier = batch.getOrder().getSupplier();
        Ingredient ingredient = c.getIngredient();
        DeliveryItem di = c.getDeliveryItem();
        BigDecimal qty = di.getDeliveredQty();
        BigDecimal price = unitPrice(di);
        BigDecimal value = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);

        c.setDisposition(ColdChainDisposition.REJECTION);
        if (c.getDecidedBy() == null) {
            c.setDecidedBy(actor);
            c.setDecidedAt(LocalDateTime.now());
        }

        ReturnOrder ro = returnService.createInternal(supplier, null, ingredient, qty,
                "冷链温度超标拒收 " + c.getCaseNo() + "（实测 " + c.getArrivalTemperature()
                        + "℃，要求不高于 " + c.getRequiredMaxTemp() + "℃）", value);
        c.setReturnOrder(ro);

        if (c.getClaim() == null) {
            BigDecimal claimAmount = claimAmountOverride != null && claimAmountOverride.signum() > 0
                    ? claimAmountOverride
                    : value.multiply(REJECT_CLAIM_RATE).setScale(2, RoundingMode.HALF_UP);
            if (claimAmount.signum() > 0) {
                Claim claim = claimService.createInternal(supplier, batch, c.getAnomaly(),
                        "冷链温度超标拒收索赔", claimAmount,
                        "[" + ingredient.getName() + "] 到货温度 " + c.getArrivalTemperature()
                                + "℃ 超过合同上限 " + c.getRequiredMaxTemp() + "℃，拒收货值 "
                                + value + "，按 " + REJECT_CLAIM_RATE.stripTrailingZeros().toPlainString()
                                + " 计质量索赔；运输时长 " + c.getTransportMinutes() + " 分钟，产品批次 "
                                + c.getProductBatchNo());
                // 测温/照片/批次证据齐全，索赔自动批准，与供应商结算直接关联扣减
                claimService.approve(claim.getId());
                c.setClaim(claim);
            }
        }

        // 对当天菜单的影响：到货当日在排/已备餐餐次，现有库存不足以覆盖需求即需替代采购与菜单调整
        BigDecimal stock = inventoryService.currentQty(ingredient.getId());
        LocalDate arrivalDate = batch.getArrivedAt().toLocalDate();
        List<MenuMealIngredient> mms = mealIngredientRepo.findMealsNeeding(ingredient.getId(),
                arrivalDate, arrivalDate, List.of(MealStatus.SCHEDULED, MealStatus.PREPARED));
        for (MenuMealIngredient mmi : mms) {
            BigDecimal shortage = mmi.getQuantity().subtract(stock).max(BigDecimal.ZERO);
            if (shortage.signum() <= 0) {
                continue;
            }
            MenuMeal meal = mmi.getMeal();
            Ingredient suggested = suggestSubstitute(ingredient);
            Supplier emergencySupplier = suggestEmergencySupplier(supplier,
                    suggested != null ? suggested.getCategory() : ingredient.getCategory());
            BigDecimal estAmount = shortage.multiply(
                    suggested != null ? suggested.getReferencePrice() : ingredient.getReferencePrice());

            SubstitutePurchase sp = new SubstitutePurchase();
            sp.setPurchaseNo(NoGen.next("SP"));
            sp.setColdChainCase(c);
            sp.setSupplier(supplier);
            sp.setIngredient(ingredient);
            sp.setMeal(meal);
            sp.setRequiredQty(shortage);
            sp.setSubstituteIngredient(suggested);
            sp.setSubstituteSupplier(emergencySupplier);
            sp.setEstimatedAmount(estAmount.setScale(2, RoundingMode.HALF_UP));
            sp.setReason("冷链超标拒收[" + ingredient.getName() + "]影响「" + meal.getDishName()
                    + "」，紧急替代采购" + (suggested != null ? "（建议替代食材 " + suggested.getName() + "）" : ""));
            substituteRepo.save(sp);

            MenuAdjustment ma = new MenuAdjustment();
            ma.setAdjustmentNo(NoGen.next("MA"));
            ma.setColdChainCase(c);
            ma.setMeal(meal);
            ma.setAction(suggested != null ? MenuAdjustmentAction.SUBSTITUTE : MenuAdjustmentAction.REPLACE_DISH);
            ma.setOriginalDishName(meal.getDishName());
            ma.setAdjustedDescription(suggested != null
                    ? "同菜换料：以[" + suggested.getName() + "]替代[" + ingredient.getName() + "]，需 " + shortage
                    : "该菜缺少[" + ingredient.getName() + "] " + shortage + ingredient.getUnit()
                    + "，需换菜或等待紧急替代采购到货");
            ma.setAffectedServings(meal.getServings());
            ma.setAdjustedBy(actor);
            ma.setNote("由冷链超标拒收单 " + c.getCaseNo() + " 自动生成");
            adjustmentRepo.save(ma);
        }
        caseRepo.save(c);
    }

    /** 替代采购单标记到货 */
    @Transactional
    public SubstitutePurchase receiveSubstitute(Long substituteId) {
        SubstitutePurchase sp = substituteRepo.findById(substituteId)
                .orElseThrow(() -> BusinessException.notFound("替代采购记录不存在: " + substituteId));
        if (sp.getStatus() != SubstitutePurchaseStatus.PLACED) {
            throw BusinessException.conflict("仅已下单状态可登记到货");
        }
        sp.setStatus(SubstitutePurchaseStatus.RECEIVED);
        return substituteRepo.save(sp);
    }

    public ColdChainCase get(Long id) {
        return caseRepo.findById(id)
                .orElseThrow(() -> BusinessException.notFound("冷链超标处置单不存在: " + id));
    }

    public CaseDetail detail(Long id) {
        ColdChainCase c = get(id);
        return new CaseDetail(c, photoRepo.findByColdChainCaseId(id),
                substituteRepo.findByColdChainCaseId(id), adjustmentRepo.findByColdChainCaseId(id));
    }

    public List<ColdChainCase> list(ColdChainDisposition disposition, ColdChainReviewStatus reviewStatus,
                                    Long batchId, Long supplierId) {
        List<ColdChainCase> base;
        if (batchId != null) {
            base = caseRepo.findByBatchId(batchId);
        } else if (supplierId != null) {
            base = caseRepo.findBySupplierId(supplierId);
        } else if (disposition != null) {
            base = caseRepo.findByDisposition(disposition);
        } else if (reviewStatus != null) {
            base = caseRepo.findByReviewStatus(reviewStatus);
        } else {
            base = caseRepo.findAll();
        }
        return base.stream()
                .filter(c -> disposition == null || c.getDisposition() == disposition)
                .filter(c -> reviewStatus == null || c.getReviewStatus() == reviewStatus)
                .filter(c -> supplierId == null || c.getBatch().getOrder().getSupplier().getId().equals(supplierId))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    public List<RestrictedStock> restrictedStocks(Long ingredientId) {
        return ingredientId != null ? restrictedRepo.findByIngredientId(ingredientId) : restrictedRepo.findAll();
    }

    public List<SubstitutePurchase> substitutesOfCase(Long caseId) {
        return substituteRepo.findByColdChainCaseId(caseId);
    }

    public List<MenuAdjustment> adjustmentsOfCase(Long caseId) {
        return adjustmentRepo.findByColdChainCaseId(caseId);
    }

    private Ingredient suggestSubstitute(Ingredient ing) {
        List<Ingredient> candidates = new ArrayList<>();
        if (ing.getSubstituteGroup() != null && !ing.getSubstituteGroup().isBlank()) {
            candidates.addAll(ingredientRepo.findBySubstituteGroup(ing.getSubstituteGroup()));
        }
        candidates.removeIf(x -> x.getId().equals(ing.getId()));
        if (candidates.isEmpty()) {
            candidates = ingredientRepo.findByCategory(ing.getCategory());
        }
        return candidates.stream()
                .filter(x -> !x.getId().equals(ing.getId()))
                .findFirst().orElse(null);
    }

    private Supplier suggestEmergencySupplier(Supplier responsible, IngredientCategory category) {
        return supplierRepo.findAll().stream()
                .filter(s -> s.isActive() && !s.getId().equals(responsible.getId()))
                .filter(s -> s.getCategories() != null && s.getCategories().contains(category.name()))
                .findFirst().orElse(null);
    }

    private static BigDecimal unitPrice(DeliveryItem di) {
        OrderItem oi = di.getOrderItem();
        return oi != null ? oi.getContractPrice() : di.getIngredient().getReferencePrice();
    }

    private static String scopeLabel(InventoryUsageScope scope) {
        return switch (scope) {
            case STAFF_MEAL -> "仅限教职工餐";
            case COOKED_ONLY -> "仅限非即食加工";
            case NORMAL -> "正常用途";
        };
    }

    public record CaseDetail(ColdChainCase caseInfo, List<ColdChainPhoto> photos,
                             List<SubstitutePurchase> substitutePurchases,
                             List<MenuAdjustment> menuAdjustments) {
    }
}
