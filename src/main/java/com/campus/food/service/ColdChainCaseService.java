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
import java.util.Comparator;
import java.util.List;

/**
 * 冷链温度超标处置服务。
 *
 * 验收暂扣后由食堂选择处置方式：
 * <ul>
 *   <li>拒收：生成退货单 + 系统自动核准的质量违约金索赔（直接进入供应商结算）；
 *       若影响当天菜单，同步生成替代采购单与菜单调整记录及家长公告草稿；</li>
 *   <li>降级为非学生餐使用：限定教职工餐或非即食加工，经采购折价审批、
 *       质检复查双闸门后入库为受限库存，领用服务强制校验用途；</li>
 *   <li>等待复检：质检复测，合格则正常入库并免除冷链合同扣罚，不合格可再拒收或降级。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ColdChainCaseService {

    /** 拒收时质量违约金索赔比例（按问题货值） */
    private static final BigDecimal REJECT_CLAIM_RATE = new BigDecimal("0.20");
    /** 菜单影响分析窗口（天） */
    private static final int MENU_IMPACT_DAYS = 7;

    private final ColdChainCaseRepo caseRepo;
    private final AcceptanceRecordRepo recordRepo;
    private final AcceptanceItemRepo itemRepo;
    private final AppUserRepo userRepo;
    private final AnomalyRecordRepo anomalyRepo;
    private final SupplierRepo supplierRepo;
    private final IngredientRepo ingredientRepo;
    private final StockLevelRepo stockLevelRepo;
    private final PurchaseOrderRepo orderRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final ParentAnnouncementRepo announcementRepo;
    private final SubstitutePurchaseRepo substitutePurchaseRepo;
    private final MenuAdjustmentRepo menuAdjustmentRepo;
    private final RestrictedStockBatchRepo restrictedBatchRepo;
    private final ReturnService returnService;
    private final ClaimService claimService;
    private final InventoryService inventoryService;
    private final AnomalyService anomalyService;

    /* ========================= 案件开启（验收服务调用） ========================= */

    /**
     * 验收暂扣后开启冷链处置案件，复用送货登记时产生且未被其他案件占用的冷链异常作为扣罚依据。
     */
    @Transactional
    public ColdChainCase openCase(AcceptanceRecord record, AcceptanceItem item,
                                  BigDecimal arrivalTemp, Integer transportDurationMinutes, String photosUrl) {
        DeliveryItem di = item.getDeliveryItem();
        Ingredient ingredient = item.getIngredient();
        DeliveryBatch batch = record.getBatch();
        Supplier supplier = batch.getOrder().getSupplier();
        OrderItem oi = di.getOrderItem();
        BigDecimal price = oi != null ? oi.getContractPrice() : ingredient.getReferencePrice();

        ColdChainCase c = new ColdChainCase();
        c.setCaseNo(NoGen.next("CC"));
        c.setRecord(record);
        c.setAcceptanceItem(item);
        c.setSupplier(supplier);
        c.setBatch(batch);
        c.setIngredient(ingredient);
        c.setQuantity(item.getHeldQty());
        c.setUnitPrice(price);
        c.setContractMinTemp(ingredient.getColdChainMinTemp());
        c.setContractMaxTemp(ingredient.getColdChainMaxTemp());
        c.setArrivalTemp(arrivalTemp);
        c.setTransportDurationMinutes(transportDurationMinutes);
        c.setProductionBatchNo(di.getProductionBatchNo());
        c.setPhotosUrl(photosUrl);
        c.setStatus(ColdChainCaseStatus.ON_HOLD);

        // 扣罚依据：优先复用本批次未占用的冷链异常，否则按食材新建一条
        BigDecimal goodsValue = item.getHeldQty().multiply(price).setScale(2, RoundingMode.HALF_UP);
        AnomalyRecord anomaly = anomalyRepo.findByBatchId(batch.getId()).stream()
                .filter(a -> a.getType() == AnomalyType.COLD_CHAIN_BREACH
                        && caseRepo.countByAnomalyId(a.getId()) == 0)
                .findFirst()
                .orElseGet(() -> anomalyService.create(supplier, batch.getOrder(), batch,
                        AnomalyType.COLD_CHAIN_BREACH, ingredient.getCategory(),
                        "验收冷链温度超标暂扣[" + ingredient.getName() + "]：实测 " + arrivalTemp
                                + "℃，合同要求 " + ingredient.getColdChainMinTemp() + "~"
                                + ingredient.getColdChainMaxTemp() + "℃，货值 " + goodsValue,
                        goodsValue));
        c.setAnomaly(anomaly);
        return caseRepo.save(c);
    }

    /* ========================= 食堂处置决定 ========================= */

    public record DecideReq(ColdChainDisposition disposition, DowngradeUsageScope usageScope, String note) {
    }

    /** 食堂对暂扣案件作出处置：拒收 / 降级 / 等待复检 */
    @Transactional
    public ColdChainCase decide(Long caseId, String username, DecideReq req) {
        ColdChainCase c = get(caseId);
        if (c.getStatus() != ColdChainCaseStatus.ON_HOLD
                && c.getStatus() != ColdChainCaseStatus.RETEST_FAILED) {
            throw BusinessException.conflict("当前案件状态[" + c.getStatus() + "]不允许作出处置决定");
        }
        AppUser user = mustUser(username);
        c.setDecidedBy(user);
        c.setDecidedAt(LocalDateTime.now());
        c.setNote(req.note());
        c.setDisposition(req.disposition());

        switch (req.disposition()) {
            case REJECT -> doReject(c, "食堂拒收：冷链温度超标" + (req.note() == null ? "" : "（" + req.note() + "）"));
            case DOWNGRADE -> {
                if (req.usageScope() == null) {
                    throw BusinessException.badRequest("降级使用必须选择限定用途：STAFF_MEAL 教职工餐 或 NON_READY_TO_EAT 非即食加工");
                }
                c.setUsageScope(req.usageScope());
                c.setStatus(ColdChainCaseStatus.DOWNGRADE_PENDING);
            }
            case RETEST -> c.setStatus(ColdChainCaseStatus.RETEST_PENDING);
        }
        return caseRepo.save(c);
    }

    /* ========================= 拒收链路 ========================= */

    /** 执行拒收：退货 + 自动索赔；影响当天菜单时生成替代采购与菜单调整 */
    private void doReject(ColdChainCase c, String reason) {
        AcceptanceItem item = c.getAcceptanceItem();
        BigDecimal qty = c.getQuantity();
        BigDecimal goodsValue = qty.multiply(c.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);

        item.setHeldQty(BigDecimal.ZERO);
        item.setRejectedQty(item.getRejectedQty().add(qty));
        item.setResult(item.getAcceptedQty().signum() > 0
                ? AcceptanceItemResult.PARTIAL : AcceptanceItemResult.REJECTED);
        itemRepo.save(item);

        returnService.createInternal(c.getSupplier(), item, c.getIngredient(), qty, reason, goodsValue);

        // 质量违约金索赔：系统按货值比例自动核准，结算周期内直接扣减
        BigDecimal claimAmount = goodsValue.multiply(REJECT_CLAIM_RATE).setScale(2, RoundingMode.HALF_UP);
        Claim claim = claimService.createInternal(c.getSupplier(), c.getBatch(), c.getAnomaly(),
                "冷链温度超标拒收违约金", claimAmount,
                "案件 " + c.getCaseNo() + "：车厢/到货温度 " + c.getArrivalTemp()
                        + "℃ 超出合同区间，按拒收货值 " + goodsValue + " 的 20% 自动核准索赔");
        claim.setStatus(ClaimStatus.APPROVED);
        claim.setResolvedAt(LocalDateTime.now());
        c.setClaim(claim);

        // 影响当天菜单 -> 替代采购 + 菜单调整 + 家长公告草稿
        handleMenuImpact(c);

        c.setStatus(ColdChainCaseStatus.REJECTED);
        c.setClosedAt(LocalDateTime.now());
        closeRecordIfDone(c);
    }

    /**
     * 拒收影响当天（含近期已排期）菜单：库存无法覆盖需求时，为每个受影响餐次生成菜单调整记录，
     * 并同步生成一张紧急替代采购单；同时生成家长公告草稿。
     */
    private void handleMenuImpact(ColdChainCase c) {
        Ingredient ing = c.getIngredient();
        LocalDate today = LocalDate.now();
        LocalDate to = today.plusDays(MENU_IMPACT_DAYS);
        BigDecimal stock = stockLevelRepo.findByIngredientId(ing.getId())
                .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);

        List<MenuMealIngredient> needs = mealIngredientRepo.findMealsNeeding(
                ing.getId(), today, to, List.of(MealStatus.SCHEDULED, MealStatus.PREPARED));
        if (needs.isEmpty()) {
            return;
        }
        BigDecimal required = needs.stream()
                .map(MenuMealIngredient::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shortage = required.subtract(stock).max(BigDecimal.ZERO);
        if (shortage.signum() <= 0) {
            return; // 现有库存可覆盖受影响菜单，无需替代采购/调菜
        }

        // 菜单调整建议：优先有库存的同替代组食材，其次同品类，都没有则建议换菜
        Ingredient menuSubstitute = suggestSubstituteWithStock(ing);
        for (MenuMealIngredient mmi : needs) {
            MenuMeal meal = mmi.getMeal();
            MenuAdjustment adj = new MenuAdjustment();
            adj.setAdjustmentNo(NoGen.next("MA"));
            adj.setColdChainCase(c);
            adj.setMeal(meal);
            adj.setOriginalDish(meal.getDishName());
            adj.setOriginalIngredient(ing);
            if (menuSubstitute != null) {
                adj.setAdjustmentType(MenuAdjustmentType.INGREDIENT_SUBSTITUTE);
                adj.setSubstituteIngredient(menuSubstitute);
                adj.setAdjustedDish(meal.getDishName() + "（" + menuSubstitute.getName() + "替代）");
                adj.setNote("拒收[" + ing.getName() + "]，建议以库存[" + menuSubstitute.getName() + "]替代");
            } else {
                adj.setAdjustmentType(MenuAdjustmentType.DISH_REPLACED);
                adj.setAdjustedDish("待食堂确定替换菜品");
                adj.setNote("拒收[" + ing.getName() + "]且无可用替代食材，需更换菜品");
            }
            menuAdjustmentRepo.save(adj);
            ensureAnnouncement(meal, ing, c.getSupplier());
        }

        // 替代采购：优先采购同替代组其他食材，否则采购原食材；供应商选非涉事、品类匹配者
        Ingredient purchaseIng = suggestSubstituteForPurchase(ing);
        Supplier altSupplier = suggestAlternativeSupplier(c.getSupplier(), purchaseIng);
        if (altSupplier != null) {
            SubstitutePurchase sp = new SubstitutePurchase();
            sp.setPurchaseNo(NoGen.next("SP"));
            sp.setColdChainCase(c);
            sp.setSupplier(altSupplier);
            sp.setIngredient(purchaseIng);
            sp.setQuantity(shortage);
            sp.setUnitPrice(purchaseIng.getReferencePrice());
            sp.setReason("冷链案件 " + c.getCaseNo() + " 拒收[" + ing.getName() + "] " + c.getQuantity()
                    + ing.getUnit() + "，受影响菜单缺口 " + shortage + ing.getUnit() + "，紧急替代采购");
            substitutePurchaseRepo.save(sp);
        }
    }

    private Ingredient suggestSubstituteWithStock(Ingredient ing) {
        List<Ingredient> candidates = substituteCandidates(ing);
        for (Ingredient cand : candidates) {
            BigDecimal s = stockLevelRepo.findByIngredientId(cand.getId())
                    .map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            if (s.signum() > 0) {
                return cand;
            }
        }
        return null;
    }

    private Ingredient suggestSubstituteForPurchase(Ingredient ing) {
        List<Ingredient> candidates = substituteCandidates(ing);
        return candidates.isEmpty() ? ing : candidates.get(0);
    }

    private List<Ingredient> substituteCandidates(Ingredient ing) {
        List<Ingredient> candidates = new ArrayList<>();
        if (ing.getSubstituteGroup() != null && !ing.getSubstituteGroup().isBlank()) {
            ingredientRepo.findBySubstituteGroup(ing.getSubstituteGroup()).stream()
                    .filter(x -> !x.getId().equals(ing.getId()))
                    .forEach(candidates::add);
        }
        if (candidates.isEmpty()) {
            ingredientRepo.findByCategory(ing.getCategory()).stream()
                    .filter(x -> !x.getId().equals(ing.getId()))
                    .forEach(candidates::add);
        }
        return candidates;
    }

    private Supplier suggestAlternativeSupplier(Supplier current, Ingredient ing) {
        List<Supplier> all = supplierRepo.findAll().stream()
                .filter(Supplier::isActive)
                .filter(s -> !s.getId().equals(current.getId()))
                .filter(s -> s.getCategories() != null && s.getCategories().contains(ing.getCategory().name()))
                .toList();
        return all.stream()
                .filter(s -> !ing.isRequiresColdChain() || s.isColdChainCapable())
                .findFirst()
                .orElse(all.stream().findFirst().orElse(null));
    }

    private void ensureAnnouncement(MenuMeal meal, Ingredient ing, Supplier supplier) {
        boolean exists = announcementRepo.findByMealId(meal.getId()).stream()
                .anyMatch(a -> a.getStatus() != AnnouncementStatus.REVOKED);
        if (exists) {
            return;
        }
        ParentAnnouncement a = new ParentAnnouncement();
        a.setMeal(meal);
        a.setTitle("【供餐调整通知】" + meal.getDate() + " " + meal.getMealType() + " " + meal.getDishName());
        a.setContent("各位家长：因供应商" + supplier.getName() + "配送的[" + ing.getName()
                + "]冷链温度超标被拒收，" + meal.getDate() + " " + meal.getMealType()
                + " 菜品「" + meal.getDishName() + "」将使用合格替代食材或调整菜品，学生用餐安全不受影响。特此告知。");
        a.setReason("冷链温度超标拒收");
        announcementRepo.save(a);
    }

    /* ========================= 降级审批 + 质检复查 ========================= */

    /** 采购对降级折价进行审批 */
    @Transactional
    public ColdChainCase approveDowngrade(Long caseId, String username, boolean approve,
                                          BigDecimal discountRate, String note) {
        ColdChainCase c = get(caseId);
        if (c.getStatus() != ColdChainCaseStatus.DOWNGRADE_PENDING) {
            throw BusinessException.conflict("仅待降级审批的案件可审批");
        }
        AppUser user = mustUser(username);
        if (approve) {
            c.setStatus(ColdChainCaseStatus.DOWNGRADE_APPROVED);
            c.setDiscountRate(discountRate == null ? new BigDecimal("0.20") : discountRate);
            c.setDowngradeApprovedBy(user);
            c.setDowngradeApprovedAt(LocalDateTime.now());
            if (note != null) {
                c.setNote(mergeNote(c.getNote(), "降级审批: " + note));
            }
        } else {
            // 降级驳回：暂扣货转退货 + 自动索赔，结案
            doReject(c, "降级使用申请被采购驳回，转拒收" + (note == null ? "" : "（" + note + "）"));
            c.setStatus(ColdChainCaseStatus.DOWNGRADE_REJECTED);
        }
        return caseRepo.save(c);
    }

    /**
     * 质检复查（降级批次入库前的强制闸门）。
     * 通过：折价入正常库存并登记受限批次（限教职工餐/非即食加工）；
     * 不通过：转拒收链路。
     */
    @Transactional
    public ColdChainCase qualityReview(Long caseId, String username, boolean passed, String note) {
        ColdChainCase c = get(caseId);
        if (c.getStatus() != ColdChainCaseStatus.DOWNGRADE_APPROVED) {
            throw BusinessException.conflict("仅折价已批准、待质检复查的案件可复查");
        }
        AppUser user = mustUser(username);
        c.setQualityReviewBy(user);
        c.setQualityReviewAt(LocalDateTime.now());
        c.setQualityReviewNote(note);

        if (!passed) {
            doReject(c, "降级批次质检复查不通过，转拒收" + (note == null ? "" : "（" + note + "）"));
            return caseRepo.save(c);
        }

        AcceptanceItem item = c.getAcceptanceItem();
        BigDecimal qty = c.getQuantity();
        item.setHeldQty(BigDecimal.ZERO);
        item.setDowngradedQty(item.getDowngradedQty().add(qty));
        item.setDowngradeStatus(DowngradeStatus.APPROVED);
        item.setDiscountRate(c.getDiscountRate());
        item.setResult(item.getAcceptedQty().signum() > 0
                ? AcceptanceItemResult.PARTIAL : AcceptanceItemResult.DOWNGRADED);
        itemRepo.save(item);

        inventoryService.change(c.getIngredient().getId(), qty, InventoryTxnType.IN,
                "COLD_DOWNGRADE", c.getId(),
                "冷链超标降级入库（限" + c.getUsageScope() + "，折价率 " + c.getDiscountRate() + "）");

        RestrictedStockBatch rb = new RestrictedStockBatch();
        rb.setColdChainCase(c);
        rb.setIngredient(c.getIngredient());
        rb.setQuantity(qty);
        rb.setRemainingQty(qty);
        rb.setUsageScope(c.getUsageScope());
        rb.setDiscountRate(c.getDiscountRate());
        restrictedBatchRepo.save(rb);

        c.setStatus(ColdChainCaseStatus.ACCEPTED);
        c.setClosedAt(LocalDateTime.now());
        closeRecordIfDone(c);
        return caseRepo.save(c);
    }

    /* ========================= 等待复检 ========================= */

    /** 质检复测：合格正常入库并免除冷链扣罚；不合格退回食堂重新处置 */
    @Transactional
    public ColdChainCase retest(Long caseId, String username, RetestOutcome outcome,
                                BigDecimal retestTemp, String note) {
        ColdChainCase c = get(caseId);
        if (c.getStatus() != ColdChainCaseStatus.RETEST_PENDING) {
            throw BusinessException.conflict("仅等待复检的案件可录入复检结果");
        }
        AppUser user = mustUser(username);
        c.setRetestBy(user);
        c.setRetestAt(LocalDateTime.now());
        c.setRetestOutcome(outcome);
        c.setRetestTemp(retestTemp);
        c.setRetestNote(note);

        if (outcome == RetestOutcome.FAILED) {
            c.setStatus(ColdChainCaseStatus.RETEST_FAILED);
            return caseRepo.save(c);
        }

        // 复检合格：正常入库；免除该案件的冷链合同扣罚（异常扣罚清零并关闭）
        AcceptanceItem item = c.getAcceptanceItem();
        BigDecimal qty = c.getQuantity();
        item.setHeldQty(BigDecimal.ZERO);
        item.setAcceptedQty(item.getAcceptedQty().add(qty));
        item.setResult(item.getRejectedQty().signum() > 0 || item.getDowngradedQty().signum() > 0
                ? AcceptanceItemResult.PARTIAL : AcceptanceItemResult.QUALIFIED);
        itemRepo.save(item);

        inventoryService.change(c.getIngredient().getId(), qty, InventoryTxnType.IN,
                "ACCEPTANCE_RETEST", c.getId(), "冷链复检合格放行入库 " + c.getRecord().getRecordNo());

        AnomalyRecord anomaly = c.getAnomaly();
        if (anomaly != null && anomaly.getStatus() != AnomalyStatus.RESOLVED) {
            anomaly.setPenaltyAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            anomaly.setStatus(AnomalyStatus.RESOLVED);
            anomaly.setResolvedAt(LocalDateTime.now());
            anomaly.setCauseCategory(CauseCategory.OTHER);
            anomaly.setHandlingNote("案件 " + c.getCaseNo() + " 复检合格（复测 " + retestTemp + "℃），免除冷链扣罚");
            anomalyRepo.save(anomaly);
        }

        c.setStatus(ColdChainCaseStatus.ACCEPTED);
        c.setClosedAt(LocalDateTime.now());
        closeRecordIfDone(c);
        return caseRepo.save(c);
    }

    /* ========================= 菜单调整确认 / 替代采购到货 ========================= */

    /** 食堂确认菜单调整；食材替代确认后同步把替代食材加入该餐次用量 */
    @Transactional
    public MenuAdjustment confirmMenuAdjustment(Long adjustmentId, String username, String note) {
        MenuAdjustment adj = menuAdjustmentRepo.findById(adjustmentId)
                .orElseThrow(() -> BusinessException.notFound("菜单调整记录不存在: " + adjustmentId));
        if (adj.getStatus() != MenuAdjustmentStatus.PROPOSED) {
            throw BusinessException.conflict("该菜单调整已确认");
        }
        AppUser user = mustUser(username);
        adj.setStatus(MenuAdjustmentStatus.CONFIRMED);
        adj.setConfirmedBy(user);
        adj.setConfirmedAt(LocalDateTime.now());
        if (note != null) {
            adj.setNote(note);
        }
        if (adj.getAdjustmentType() == MenuAdjustmentType.INGREDIENT_SUBSTITUTE
                && adj.getSubstituteIngredient() != null) {
            MenuMealIngredient origin = mealIngredientRepo.findByMealId(adj.getMeal().getId()).stream()
                    .filter(mmi -> mmi.getIngredient().getId().equals(adj.getOriginalIngredient().getId()))
                    .findFirst().orElse(null);
            BigDecimal qty = origin != null ? origin.getQuantity() : BigDecimal.ONE;
            MenuMealIngredient mmi = new MenuMealIngredient();
            mmi.setMeal(adj.getMeal());
            mmi.setIngredient(adj.getSubstituteIngredient());
            mmi.setQuantity(qty);
            mealIngredientRepo.save(mmi);
        }
        return menuAdjustmentRepo.save(adj);
    }

    /** 替代采购到货入库 */
    @Transactional
    public SubstitutePurchase receiveSubstitute(Long purchaseId) {
        SubstitutePurchase sp = substitutePurchaseRepo.findById(purchaseId)
                .orElseThrow(() -> BusinessException.notFound("替代采购单不存在: " + purchaseId));
        if (sp.getStatus() != SubstitutePurchaseStatus.PLACED) {
            throw BusinessException.conflict("仅已下单状态可登记到货");
        }
        sp.setStatus(SubstitutePurchaseStatus.RECEIVED);
        sp.setReceivedAt(LocalDateTime.now());
        inventoryService.change(sp.getIngredient().getId(), sp.getQuantity(), InventoryTxnType.IN,
                "SUBSTITUTE_PURCHASE", sp.getId(), "冷链拒收紧急替代采购到货 " + sp.getPurchaseNo());
        return substitutePurchaseRepo.save(sp);
    }

    /* ========================= 结案与查询 ========================= */

    /** 案件全部处置完毕后，重算验收单结果并把订货单置为已验收 */
    private void closeRecordIfDone(ColdChainCase c) {
        AcceptanceRecord record = c.getRecord();
        boolean stillHeld = itemRepo.findByRecordId(record.getId()).stream()
                .anyMatch(i -> i.getHeldQty().signum() > 0);
        if (stillHeld) {
            return;
        }
        List<AcceptanceItem> items = itemRepo.findByRecordId(record.getId());
        boolean anyAccepted = items.stream().anyMatch(i -> i.getAcceptedQty().signum() > 0);
        boolean anyBad = items.stream().anyMatch(i ->
                i.getRejectedQty().signum() > 0 || i.getDowngradedQty().signum() > 0);
        record.setOverallResult(!anyBad ? AcceptanceOverallResult.QUALIFIED
                : anyAccepted ? AcceptanceOverallResult.PARTIAL_QUALIFIED
                : AcceptanceOverallResult.REJECTED);
        recordRepo.save(record);

        PurchaseOrder order = record.getBatch().getOrder();
        order.setStatus(OrderStatus.ACCEPTED);
        orderRepo.save(order);
    }

    public ColdChainCase get(Long id) {
        return caseRepo.findById(id).orElseThrow(() -> BusinessException.notFound("冷链处置案件不存在: " + id));
    }

    public CaseDetail detail(Long id) {
        ColdChainCase c = get(id);
        return new CaseDetail(c,
                substitutePurchaseRepo.findByColdChainCaseId(id),
                menuAdjustmentRepo.findByColdChainCaseId(id));
    }

    public List<ColdChainCase> list(Long supplierId, ColdChainCaseStatus status) {
        List<ColdChainCase> base = supplierId != null ? caseRepo.findBySupplierId(supplierId) : caseRepo.findAll();
        return base.stream()
                .filter(c -> status == null || c.getStatus() == status)
                .sorted(Comparator.comparing(ColdChainCase::getCreatedAt).reversed())
                .toList();
    }

    public List<SubstitutePurchase> listSubstitutes(Long supplierId) {
        return supplierId != null ? substitutePurchaseRepo.findBySupplierId(supplierId)
                : substitutePurchaseRepo.findAll();
    }

    private AppUser mustUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> BusinessException.notFound("用户不存在: " + username));
    }

    private static String mergeNote(String oldNote, String addition) {
        return oldNote == null || oldNote.isBlank() ? addition : oldNote + " | " + addition;
    }

    public record CaseDetail(ColdChainCase caseInfo,
                             List<SubstitutePurchase> substitutePurchases,
                             List<MenuAdjustment> menuAdjustments) {
    }
}
