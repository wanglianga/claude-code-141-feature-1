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
import java.util.ArrayList;
import java.util.List;

/**
 * 验收服务：核对温度/外观/票证/重量/腐烂比例/抽检，
 * 合格入库、拒收退货、降级待审批；自动识别少送、腐烂超标、票证缺失、已备餐无法拒收等异常。
 */
@Service
@RequiredArgsConstructor
public class AcceptanceService {

    private final AcceptanceRecordRepo recordRepo;
    private final AcceptanceItemRepo itemRepo;
    private final DeliveryBatchRepo batchRepo;
    private final DeliveryItemRepo deliveryItemRepo;
    private final AppUserRepo userRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final PurchaseOrderRepo orderRepo;
    private final ColdChainCaseRepo coldChainCaseRepo;
    private final InventoryService inventoryService;
    private final AnomalyService anomalyService;
    private final ReturnService returnService;
    private final ClaimService claimService;

    public record AcceptanceItemInput(Long deliveryItemId, BigDecimal acceptedQty, BigDecimal rejectedQty,
                                      BigDecimal downgradedQty, BigDecimal rotRatio, String rejectReason) {
    }

    @Transactional
    public AcceptanceRecord create(String inspectorUsername, Long batchId,
                                   BigDecimal measuredTemp, Boolean temperatureOk, Boolean appearanceOk,
                                   String samplingResult, String notes,
                                   List<AcceptanceItemInput> items) {
        DeliveryBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> BusinessException.notFound("送货批次不存在: " + batchId));
        if (recordRepo.existsByBatchId(batchId)) {
            throw BusinessException.conflict("该送货批次已验收，不能重复验收");
        }
        if (items == null || items.isEmpty()) {
            throw BusinessException.badRequest("验收明细不能为空");
        }
        AppUser inspector = userRepo.findByUsername(inspectorUsername)
                .orElseThrow(() -> BusinessException.notFound("验收员不存在: " + inspectorUsername));
        PurchaseOrder order = batch.getOrder();
        Supplier supplier = order.getSupplier();

        AcceptanceRecord record = new AcceptanceRecord();
        record.setRecordNo(NoGen.next("AR"));
        record.setBatch(batch);
        record.setInspector(inspector);
        record.setMeasuredTemp(measuredTemp);
        record.setTemperatureOk(temperatureOk);
        record.setAppearanceOk(appearanceOk);
        record.setSamplingResult(samplingResult);
        record.setNotes(notes);

        boolean anyNotQualified = false;
        boolean anyAccepted = false;
        List<AcceptanceItem> savedItems = new ArrayList<>();
        // 待执行的后续动作（入库/退货），需在验收单与明细落库后执行
        List<Runnable> postActions = new ArrayList<>();
        java.util.Set<Long> submittedDiIds = new java.util.HashSet<>();

        for (AcceptanceItemInput in : items) {
            DeliveryItem di = deliveryItemRepo.findById(in.deliveryItemId())
                    .orElseThrow(() -> BusinessException.notFound("送货明细不存在: " + in.deliveryItemId()));
            if (!di.getBatch().getId().equals(batch.getId())) {
                throw BusinessException.badRequest("送货明细不属于该批次: " + in.deliveryItemId());
            }
            if (!submittedDiIds.add(di.getId())) {
                throw BusinessException.badRequest("送货明细重复验收: " + in.deliveryItemId());
            }
            Ingredient ingredient = di.getIngredient();
            OrderItem oi = di.getOrderItem();
            BigDecimal price = oi != null ? oi.getContractPrice() : ingredient.getReferencePrice();
            BigDecimal orderedQty = oi != null ? oi.getQuantity() : BigDecimal.ZERO;

            // 已登记冷链温度超标处置单的明细：以处置/质检结论落账，
            // 入库（含限制用途）、退货、索赔均已由冷链处置流程完成，这里不重复执行
            var coldCaseOpt = coldChainCaseRepo.findByDeliveryItemId(di.getId());
            if (coldCaseOpt.isPresent()) {
                AcceptanceItem coldItem = buildColdChainItem(coldCaseOpt.get(), record, di, ingredient, orderedQty);
                savedItems.add(coldItem);
                if (coldItem.getResult() != AcceptanceItemResult.QUALIFIED) {
                    anyNotQualified = true;
                }
                if (coldItem.getAcceptedQty().signum() > 0) {
                    anyAccepted = true;
                }
                continue;
            }

            BigDecimal accepted = nvl(in.acceptedQty());
            BigDecimal rejected = nvl(in.rejectedQty());
            BigDecimal downgraded = nvl(in.downgradedQty());
            BigDecimal sum = accepted.add(rejected).add(downgraded);
            if (sum.compareTo(di.getDeliveredQty()) != 0) {
                throw BusinessException.badRequest("[" + ingredient.getName() + "] 验收数量合计 "
                        + sum + " 与送货数量 " + di.getDeliveredQty() + " 不一致");
            }

            AcceptanceItemResult result;
            // 食堂已备餐 -> 无法拒收，转附条件接收并自动索赔
            if (rejected.signum() > 0
                    && mealIngredientRepo.countPreparedMealsNeeding(ingredient.getId(), LocalDate.now()) > 0) {
                BigDecimal conditionalQty = rejected;
                accepted = accepted.add(rejected);
                rejected = BigDecimal.ZERO;
                AnomalyRecord anomaly = anomalyService.create(supplier, order, batch,
                        AnomalyType.CANNOT_REJECT_PREPARED, ingredient.getCategory(),
                        "食堂已备餐，无法拒收[" + ingredient.getName() + "] " + conditionalQty
                                + ingredient.getUnit() + "，转附条件接收",
                        conditionalQty.multiply(price));
                claimService.createInternal(supplier, batch, anomaly, "无法拒收折价索赔",
                        conditionalQty.multiply(price).multiply(new BigDecimal("0.5"))
                                .setScale(2, RoundingMode.HALF_UP),
                        "已备餐无法拒收，按货值 50% 索赔折价");
                result = AcceptanceItemResult.CONDITIONAL_ACCEPT;
            } else if (rejected.signum() > 0 && accepted.signum() == 0 && downgraded.signum() == 0) {
                result = AcceptanceItemResult.REJECTED;
            } else if (downgraded.signum() > 0 && rejected.signum() == 0 && accepted.signum() == 0) {
                result = AcceptanceItemResult.DOWNGRADED;
            } else if (rejected.signum() > 0 || downgraded.signum() > 0) {
                result = AcceptanceItemResult.PARTIAL;
            } else {
                result = AcceptanceItemResult.QUALIFIED;
            }

            AcceptanceItem item = new AcceptanceItem();
            item.setRecord(record);
            item.setDeliveryItem(di);
            item.setIngredient(ingredient);
            item.setOrderedQty(orderedQty);
            item.setDeliveredQty(di.getDeliveredQty());
            item.setAcceptedQty(accepted);
            item.setRejectedQty(rejected);
            item.setDowngradedQty(downgraded);
            item.setRotRatio(in.rotRatio());
            item.setResult(result);
            item.setRejectReason(in.rejectReason());
            if (downgraded.signum() > 0) {
                item.setDowngradeStatus(DowngradeStatus.PENDING);
            }
            item.setRecord(record);
            savedItems.add(item);

            // 少送异常（按订货明细累计送货量判定，避免分批送货误报）
            if (oi != null) {
                BigDecimal deliveredBefore = deliveryItemRepo
                        .sumDeliveredExcludingBatch(oi.getId(), batch.getId());
                BigDecimal remaining = oi.getQuantity().subtract(deliveredBefore).max(BigDecimal.ZERO);
                if (di.getDeliveredQty().compareTo(remaining) < 0) {
                    BigDecimal shortQty = remaining.subtract(di.getDeliveredQty());
                    anomalyService.create(supplier, order, batch, AnomalyType.SHORT_DELIVERY,
                            ingredient.getCategory(),
                            "[" + ingredient.getName() + "] 订购 " + oi.getQuantity()
                                    + "，此前累计已送 " + deliveredBefore + "，本次送 "
                                    + di.getDeliveredQty() + "，少送 " + shortQty + ingredient.getUnit(),
                            shortQty.multiply(price));
                }
            }
            // 腐烂比例异常
            BigDecimal threshold = switch (ingredient.getCategory()) {
                case VEGETABLE, FRUIT -> new BigDecimal("0.10");
                default -> new BigDecimal("0.05");
            };
            if (in.rotRatio() != null && in.rotRatio().compareTo(threshold) > 0) {
                anomalyService.create(supplier, order, batch, AnomalyType.HIGH_ROT_RATIO,
                        ingredient.getCategory(),
                        "[" + ingredient.getName() + "] 腐烂比例 " + in.rotRatio()
                                + " 超过阈值 " + threshold,
                        di.getDeliveredQty().multiply(in.rotRatio()).multiply(price));
            }
            // 票证缺失（肉类需检疫票）
            if (ingredient.getCategory() == IngredientCategory.MEAT
                    && (di.getQuarantineCertNo() == null || di.getQuarantineCertNo().isBlank())) {
                anomalyService.create(supplier, order, batch, AnomalyType.MISSING_DOCUMENTS,
                        ingredient.getCategory(),
                        "[" + ingredient.getName() + "] 缺少动物检疫合格证明", null);
            }

            // 库存与退货（延迟到验收单与明细落库后执行）
            final BigDecimal acceptedFinal = accepted;
            final BigDecimal rejectedFinal = rejected;
            final String reasonFinal = in.rejectReason() == null ? "验收不合格" : in.rejectReason();
            final BigDecimal priceFinal = price;
            if (accepted.signum() > 0) {
                postActions.add(() -> inventoryService.change(ingredient.getId(), acceptedFinal,
                        InventoryTxnType.IN, "ACCEPTANCE", record.getId(),
                        "验收入库 " + record.getRecordNo()));
                anyAccepted = true;
            }
            if (rejected.signum() > 0) {
                postActions.add(() -> returnService.createInternal(supplier, item, ingredient,
                        rejectedFinal, reasonFinal, rejectedFinal.multiply(priceFinal)));
            }
            if (result != AcceptanceItemResult.QUALIFIED) {
                anyNotQualified = true;
            }
        }

        record.setOverallResult(!anyNotQualified ? AcceptanceOverallResult.QUALIFIED
                : anyAccepted ? AcceptanceOverallResult.PARTIAL_QUALIFIED
                : AcceptanceOverallResult.REJECTED);

        // 批次内全部送货明细都必须验收（含已走冷链超标处置流程的明细）
        List<Long> batchDiIds = deliveryItemRepo.findByBatchId(batchId).stream()
                .map(DeliveryItem::getId).toList();
        if (!submittedDiIds.containsAll(batchDiIds)) {
            throw BusinessException.badRequest("存在未验收的送货明细，请补全后再提交");
        }

        recordRepo.save(record);
        savedItems.forEach(itemRepo::save);
        postActions.forEach(Runnable::run);

        order.setStatus(OrderStatus.ACCEPTED);
        orderRepo.save(order);
        return record;
    }

    /** 降级使用审批：批准则按折价入库，驳回则转退货 */
    @Transactional
    public AcceptanceItem approveDowngrade(Long acceptanceItemId, boolean approve, BigDecimal discountRate) {
        AcceptanceItem item = itemRepo.findById(acceptanceItemId)
                .orElseThrow(() -> BusinessException.notFound("验收明细不存在: " + acceptanceItemId));
        if (item.getDowngradeStatus() != DowngradeStatus.PENDING) {
            throw BusinessException.conflict("该明细没有待审批的降级数量");
        }
        Ingredient ingredient = item.getIngredient();
        DeliveryItem di = item.getDeliveryItem();
        OrderItem oi = di.getOrderItem();
        BigDecimal price = oi != null ? oi.getContractPrice() : ingredient.getReferencePrice();
        Supplier supplier = item.getRecord().getBatch().getOrder().getSupplier();

        if (approve) {
            item.setDowngradeStatus(DowngradeStatus.APPROVED);
            item.setDiscountRate(discountRate == null ? new BigDecimal("0.20") : discountRate);
            inventoryService.change(ingredient.getId(), item.getDowngradedQty(), InventoryTxnType.IN,
                    "DOWNGRADE", item.getId(), "降级使用入库（折价率 " + item.getDiscountRate() + "）");
        } else {
            item.setDowngradeStatus(DowngradeStatus.REJECTED);
            BigDecimal qty = item.getDowngradedQty();
            item.setRejectedQty(item.getRejectedQty().add(qty));
            item.setDowngradedQty(BigDecimal.ZERO);
            item.setResult(item.getAcceptedQty().signum() > 0
                    ? AcceptanceItemResult.PARTIAL : AcceptanceItemResult.REJECTED);
            returnService.createInternal(supplier, item, ingredient, qty,
                    "降级审批驳回转退货", qty.multiply(price));
        }
        return itemRepo.save(item);
    }

    public AcceptanceRecord get(Long id) {
        return recordRepo.findById(id).orElseThrow(() -> BusinessException.notFound("验收记录不存在: " + id));
    }

    public RecordDetail detail(Long id) {
        return new RecordDetail(get(id), itemRepo.findByRecordId(id));
    }

    public List<AcceptanceItem> itemsOfRecord(Long recordId) {
        return itemRepo.findByRecordId(recordId);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 冷链温度超标处置明细按处置结论落账：
     * 拒收 -> 拒收退货；降级复查合格 -> 降级（已入限制用途库存）；复检合格 -> 合格。
     * 尚未完成质检复查/复检的处置单不允许验收。
     */
    private AcceptanceItem buildColdChainItem(ColdChainCase cc, AcceptanceRecord record,
                                              DeliveryItem di, Ingredient ingredient, BigDecimal orderedQty) {
        AcceptanceItem item = new AcceptanceItem();
        item.setRecord(record);
        item.setDeliveryItem(di);
        item.setIngredient(ingredient);
        item.setOrderedQty(orderedQty);
        item.setDeliveredQty(di.getDeliveredQty());
        BigDecimal qty = di.getDeliveredQty();

        switch (cc.getDisposition()) {
            case REJECTION -> {
                item.setAcceptedQty(BigDecimal.ZERO);
                item.setRejectedQty(qty);
                item.setDowngradedQty(BigDecimal.ZERO);
                item.setResult(AcceptanceItemResult.REJECTED);
                item.setDowngradeStatus(DowngradeStatus.NONE);
                item.setRejectReason("冷链温度超标拒收（处置单 " + cc.getCaseNo()
                        + "，实测 " + cc.getArrivalTemperature() + "℃）");
            }
            case DOWNGRADE -> {
                if (cc.getReviewStatus() != com.campus.food.domain.enums.ColdChainReviewStatus.PASSED) {
                    throw BusinessException.conflict("冷链降级处置单 " + cc.getCaseNo()
                            + " 尚未完成质检复查，不能验收");
                }
                item.setAcceptedQty(BigDecimal.ZERO);
                item.setRejectedQty(BigDecimal.ZERO);
                item.setDowngradedQty(qty);
                item.setResult(AcceptanceItemResult.DOWNGRADED);
                item.setDowngradeStatus(DowngradeStatus.APPROVED);
                item.setDiscountRate(cc.getDiscountRate());
                item.setRejectReason("冷链超标降级为非学生餐使用（处置单 " + cc.getCaseNo() + "）");
            }
            case RECHECK -> {
                if (cc.getReviewStatus() != com.campus.food.domain.enums.ColdChainReviewStatus.PASSED) {
                    throw BusinessException.conflict("冷链处置单 " + cc.getCaseNo()
                            + " 尚在等待复检结果，不能验收");
                }
                item.setAcceptedQty(qty);
                item.setRejectedQty(BigDecimal.ZERO);
                item.setDowngradedQty(BigDecimal.ZERO);
                item.setResult(AcceptanceItemResult.QUALIFIED);
                item.setDowngradeStatus(DowngradeStatus.NONE);
            }
            default -> throw BusinessException.conflict("冷链处置单 " + cc.getCaseNo()
                    + " 食堂尚未做出处置决策，不能验收");
        }
        return item;
    }

    public record RecordDetail(AcceptanceRecord record, List<AcceptanceItem> items) {
    }
}
