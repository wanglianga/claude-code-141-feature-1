package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 验收服务：核对温度/外观/票证/重量/腐烂比例/抽检，
 * 合格入库、拒收退货、降级待审批；自动识别少送、腐烂超标、票证缺失、已备餐无法拒收等异常。
 *
 * 冷链温度超标专项：验收员录入到货温度、运输时长、产品批次、抽检照片后，
 * 可将问题食材整批暂扣（heldQty，验收单 PENDING），由食堂在
 * {@link ColdChainCaseService} 中选择拒收 / 降级为非学生餐使用 / 等待复检。
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
    private final InventoryService inventoryService;
    private final AnomalyService anomalyService;
    private final ReturnService returnService;
    private final ClaimService claimService;
    private final ColdChainCaseService coldChainCaseService;

    public record AcceptanceItemInput(Long deliveryItemId, BigDecimal acceptedQty, BigDecimal rejectedQty,
                                      BigDecimal downgradedQty, BigDecimal rotRatio, String rejectReason,
                                      Boolean coldChainHeld) {
    }

    @Transactional
    public AcceptanceRecord create(String inspectorUsername, Long batchId,
                                   BigDecimal measuredTemp, Boolean temperatureOk, Boolean appearanceOk,
                                   String samplingResult, String notes,
                                   Integer transportDurationMinutes, String photosUrl,
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
        record.setTransportDurationMinutes(transportDurationMinutes);
        record.setPhotosUrl(photosUrl);

        boolean anyNotQualified = false;
        boolean anyAccepted = false;
        boolean anyHeld = false;
        List<AcceptanceItem> savedItems = new ArrayList<>();
        // 待执行的后续动作（入库/退货），需在验收单与明细落库后执行
        List<Runnable> postActions = new ArrayList<>();
        // 冷链温度超标暂扣的明细，落库后生成处置案件
        List<AcceptanceItem> heldItems = new ArrayList<>();

        for (AcceptanceItemInput in : items) {
            DeliveryItem di = deliveryItemRepo.findById(in.deliveryItemId())
                    .orElseThrow(() -> BusinessException.notFound("送货明细不存在: " + in.deliveryItemId()));
            if (!di.getBatch().getId().equals(batch.getId())) {
                throw BusinessException.badRequest("送货明细不属于该批次: " + in.deliveryItemId());
            }
            Ingredient ingredient = di.getIngredient();
            OrderItem oi = di.getOrderItem();
            BigDecimal price = oi != null ? oi.getContractPrice() : ingredient.getReferencePrice();
            BigDecimal orderedQty = oi != null ? oi.getQuantity() : BigDecimal.ZERO;

            BigDecimal accepted = nvl(in.acceptedQty());
            BigDecimal rejected = nvl(in.rejectedQty());
            BigDecimal downgraded = nvl(in.downgradedQty());
            boolean coldChainHeld = Boolean.TRUE.equals(in.coldChainHeld());
            BigDecimal sum = accepted.add(rejected).add(downgraded);

            AcceptanceItem item = new AcceptanceItem();
            item.setRecord(record);
            item.setDeliveryItem(di);
            item.setIngredient(ingredient);
            item.setOrderedQty(orderedQty);
            item.setDeliveredQty(di.getDeliveredQty());
            item.setRotRatio(in.rotRatio());
            item.setRejectReason(in.rejectReason());

            if (coldChainHeld) {
                // 冷链温度超标：整批暂扣，待食堂处置，暂不入库存/退货/结算
                if (sum.signum() != 0) {
                    throw BusinessException.badRequest("[" + ingredient.getName()
                            + "] 冷链温度超标暂扣时不能同时填写合格/拒收/降级数量");
                }
                if (measuredTemp == null || !isColdChainBreach(ingredient, measuredTemp)) {
                    throw BusinessException.badRequest("[" + ingredient.getName()
                            + "] 不满足冷链温度超标暂扣条件（需录入超出合同区间的到货温度）");
                }
                item.setAcceptedQty(BigDecimal.ZERO);
                item.setRejectedQty(BigDecimal.ZERO);
                item.setDowngradedQty(BigDecimal.ZERO);
                item.setHeldQty(di.getDeliveredQty());
                item.setColdChainBreach(true);
                item.setResult(AcceptanceItemResult.HELD);
                item.setDowngradeStatus(DowngradeStatus.NONE);
                savedItems.add(item);
                heldItems.add(item);
                anyHeld = true;
                detectShortDelivery(supplier, order, batch, ingredient, oi, di, price);
                continue;
            }

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
                                .setScale(2, java.math.RoundingMode.HALF_UP),
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

            item.setAcceptedQty(accepted);
            item.setRejectedQty(rejected);
            item.setDowngradedQty(downgraded);
            item.setHeldQty(BigDecimal.ZERO);
            item.setResult(result);
            if (downgraded.signum() > 0) {
                item.setDowngradeStatus(DowngradeStatus.PENDING);
            }
            item.setRecord(record);
            savedItems.add(item);

            detectShortDelivery(supplier, order, batch, ingredient, oi, di, price);
            detectRot(supplier, order, batch, ingredient, di, in, price);
            detectMissingDocuments(supplier, order, batch, ingredient, di);

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

        if (anyHeld) {
            record.setOverallResult(AcceptanceOverallResult.PENDING);
        } else {
            record.setOverallResult(!anyNotQualified ? AcceptanceOverallResult.QUALIFIED
                    : anyAccepted ? AcceptanceOverallResult.PARTIAL_QUALIFIED
                    : AcceptanceOverallResult.REJECTED);
        }
        recordRepo.save(record);
        savedItems.forEach(itemRepo::save);
        postActions.forEach(Runnable::run);

        // 暂扣明细生成冷链处置案件（复用送货登记时已产生的冷链异常，避免重复扣罚）
        for (AcceptanceItem held : heldItems) {
            coldChainCaseService.openCase(record, held, measuredTemp, transportDurationMinutes, photosUrl);
        }

        // 有暂扣食材待处置时订单保持已到货，案件全部结案后再置验收完成
        if (!anyHeld) {
            order.setStatus(OrderStatus.ACCEPTED);
            orderRepo.save(order);
        }
        return record;
    }

    /** 冷链温度是否超出合同要求区间 */
    public static boolean isColdChainBreach(Ingredient ingredient, BigDecimal measuredTemp) {
        if (!ingredient.isRequiresColdChain()) {
            return false;
        }
        boolean tooHigh = ingredient.getColdChainMaxTemp() != null
                && measuredTemp.compareTo(ingredient.getColdChainMaxTemp()) > 0;
        boolean tooLow = ingredient.getColdChainMinTemp() != null
                && measuredTemp.compareTo(ingredient.getColdChainMinTemp()) < 0;
        return tooHigh || tooLow;
    }

    /** 少送异常（按订货明细累计送货量判定，避免分批送货误报） */
    private void detectShortDelivery(Supplier supplier, PurchaseOrder order, DeliveryBatch batch,
                                     Ingredient ingredient, OrderItem oi, DeliveryItem di, BigDecimal price) {
        if (oi == null) {
            return;
        }
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

    /** 腐烂比例异常 */
    private void detectRot(Supplier supplier, PurchaseOrder order, DeliveryBatch batch,
                           Ingredient ingredient, DeliveryItem di, AcceptanceItemInput in, BigDecimal price) {
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
    }

    /** 票证缺失（肉类需检疫票） */
    private void detectMissingDocuments(Supplier supplier, PurchaseOrder order, DeliveryBatch batch,
                                        Ingredient ingredient, DeliveryItem di) {
        if (ingredient.getCategory() == IngredientCategory.MEAT
                && (di.getQuarantineCertNo() == null || di.getQuarantineCertNo().isBlank())) {
            anomalyService.create(supplier, order, batch, AnomalyType.MISSING_DOCUMENTS,
                    ingredient.getCategory(),
                    "[" + ingredient.getName() + "] 缺少动物检疫合格证明", null);
        }
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

    public record RecordDetail(AcceptanceRecord record, List<AcceptanceItem> items) {
    }
}
