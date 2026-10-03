package com.campus.food.service;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 冷链温度超标处置服务单元测试：
 * 拒收（退货+自动索赔+替代采购+菜单调整）、降级双闸门、复检合格放行、复检失败转拒收。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ColdChainCaseServiceTest {

    @Mock ColdChainCaseRepo caseRepo;
    @Mock AcceptanceRecordRepo recordRepo;
    @Mock AcceptanceItemRepo itemRepo;
    @Mock AppUserRepo userRepo;
    @Mock AnomalyRecordRepo anomalyRepo;
    @Mock SupplierRepo supplierRepo;
    @Mock IngredientRepo ingredientRepo;
    @Mock StockLevelRepo stockLevelRepo;
    @Mock PurchaseOrderRepo orderRepo;
    @Mock MenuMealIngredientRepo mealIngredientRepo;
    @Mock ParentAnnouncementRepo announcementRepo;
    @Mock SubstitutePurchaseRepo substitutePurchaseRepo;
    @Mock MenuAdjustmentRepo menuAdjustmentRepo;
    @Mock RestrictedStockBatchRepo restrictedBatchRepo;
    @Mock ReturnService returnService;
    @Mock ClaimService claimService;
    @Mock InventoryService inventoryService;
    @Mock AnomalyService anomalyService;

    @InjectMocks ColdChainCaseService service;

    private Ingredient milk;
    private Ingredient egg;
    private Supplier dairySupplier;
    private Supplier altSupplier;
    private AppUser canteenUser;
    private AppUser qualityUser;
    private DeliveryBatch batch;
    private PurchaseOrder order;
    private AcceptanceRecord record;
    private AcceptanceItem item;
    private AnomalyRecord anomaly;
    private MenuMeal breakfast;
    private MenuMealIngredient milkNeed;

    @BeforeEach
    void setUp() {
        milk = new Ingredient();
        milk.setId(10L);
        milk.setName("鲜牛奶");
        milk.setCategory(IngredientCategory.DAIRY);
        milk.setUnit("L");
        milk.setRequiresColdChain(true);
        milk.setColdChainMinTemp(new BigDecimal("0"));
        milk.setColdChainMaxTemp(new BigDecimal("6"));
        milk.setReferencePrice(new BigDecimal("4.00"));

        egg = new Ingredient();
        egg.setId(11L);
        egg.setName("鸡蛋");
        egg.setCategory(IngredientCategory.DAIRY);
        egg.setUnit("kg");
        egg.setRequiresColdChain(false);
        egg.setReferencePrice(new BigDecimal("10.00"));

        dairySupplier = new Supplier();
        dairySupplier.setId(1L);
        dairySupplier.setName("康源乳业");
        dairySupplier.setCategories("DAIRY");
        dairySupplier.setColdChainCapable(true);
        dairySupplier.setActive(true);

        altSupplier = new Supplier();
        altSupplier.setId(2L);
        altSupplier.setName("备选蛋乳商");
        altSupplier.setCategories("DAIRY");
        altSupplier.setColdChainCapable(true);
        altSupplier.setActive(true);

        canteenUser = new AppUser();
        canteenUser.setId(20L);
        canteenUser.setUsername("canteen");
        qualityUser = new AppUser();
        qualityUser.setId(21L);
        qualityUser.setUsername("quality");

        order = new PurchaseOrder();
        order.setId(30L);
        order.setSupplier(dairySupplier);
        batch = new DeliveryBatch();
        batch.setId(40L);
        batch.setOrder(order);
        record = new AcceptanceRecord();
        record.setId(50L);
        record.setBatch(batch);

        DeliveryItem di = new DeliveryItem();
        di.setId(60L);
        di.setIngredient(milk);
        di.setBatch(batch);
        di.setDeliveredQty(new BigDecimal("100"));

        item = new AcceptanceItem();
        item.setId(70L);
        item.setRecord(record);
        item.setDeliveryItem(di);
        item.setIngredient(milk);
        item.setDeliveredQty(new BigDecimal("100"));
        item.setAcceptedQty(BigDecimal.ZERO);
        item.setRejectedQty(BigDecimal.ZERO);
        item.setDowngradedQty(BigDecimal.ZERO);
        item.setHeldQty(new BigDecimal("100"));

        anomaly = new AnomalyRecord();
        anomaly.setId(80L);
        anomaly.setType(AnomalyType.COLD_CHAIN_BREACH);
        anomaly.setStatus(AnomalyStatus.OPEN);
        anomaly.setPenaltyAmount(new BigDecimal("300.00"));
        anomaly.setSupplier(dairySupplier);

        breakfast = new MenuMeal();
        breakfast.setId(90L);
        breakfast.setDate(java.time.LocalDate.now());
        breakfast.setMealType(MealType.BREAKFAST);
        breakfast.setDishName("牛奶鸡蛋早餐");
        breakfast.setStatus(MealStatus.SCHEDULED);
        milkNeed = new MenuMealIngredient();
        milkNeed.setMeal(breakfast);
        milkNeed.setIngredient(milk);
        milkNeed.setQuantity(new BigDecimal("120"));

        when(userRepo.findByUsername("canteen")).thenReturn(Optional.of(canteenUser));
        when(userRepo.findByUsername("quality")).thenReturn(Optional.of(qualityUser));
        when(itemRepo.findByRecordId(50L)).thenReturn(List.of(item));
        when(caseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(menuAdjustmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(substitutePurchaseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(anomalyRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(returnService.createInternal(any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReturnOrder());
        when(mealIngredientRepo.findMealsNeeding(anyLong(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private ColdChainCase newCase(ColdChainCaseStatus status) {
        ColdChainCase c = new ColdChainCase();
        c.setId(100L);
        c.setCaseNo("CC-TEST-1");
        c.setRecord(record);
        c.setAcceptanceItem(item);
        c.setSupplier(dairySupplier);
        c.setBatch(batch);
        c.setIngredient(milk);
        c.setQuantity(new BigDecimal("100"));
        c.setUnitPrice(new BigDecimal("4.00"));
        c.setContractMinTemp(new BigDecimal("0"));
        c.setContractMaxTemp(new BigDecimal("6"));
        c.setArrivalTemp(new BigDecimal("12"));
        c.setTransportDurationMinutes(180);
        c.setProductionBatchNo("MILK20261003");
        c.setPhotosUrl("http://img/photo1.jpg");
        c.setAnomaly(anomaly);
        c.setStatus(status);
        when(caseRepo.findById(100L)).thenReturn(Optional.of(c));
        return c;
    }

    private void stubTodayMenuShortage() {
        // 当天菜单需要 120L 牛奶，库存 60L，缺口 60L
        StockLevel milkStock = new StockLevel();
        milkStock.setIngredient(milk);
        milkStock.setQuantity(new BigDecimal("60"));
        when(stockLevelRepo.findByIngredientId(10L)).thenReturn(Optional.of(milkStock));
        when(mealIngredientRepo.findMealsNeeding(eq(10L), any(), any(), any()))
                .thenReturn(List.of(milkNeed));
        // 同品类有鸡蛋且有库存 -> 菜单建议鸡蛋替代
        StockLevel eggStock = new StockLevel();
        eggStock.setIngredient(egg);
        eggStock.setQuantity(new BigDecimal("50"));
        when(stockLevelRepo.findByIngredientId(11L)).thenReturn(Optional.of(eggStock));
        when(ingredientRepo.findByCategory(IngredientCategory.DAIRY)).thenReturn(List.of(egg));
        when(ingredientRepo.findBySubstituteGroup(any())).thenReturn(List.of());
        // 备选供应商可紧急采购鸡蛋
        when(supplierRepo.findAll()).thenReturn(List.of(dairySupplier, altSupplier));
        when(announcementRepo.findByMealId(90L)).thenReturn(List.of());
        when(mealIngredientRepo.findByMealId(90L)).thenReturn(List.of(milkNeed));
    }

    @Test
    void coldChainBreachBoundary() {
        // 合同 0~6℃：6℃ 合格，6.01℃ 超标，-0.1℃ 超标
        assertFalse(AcceptanceService.isColdChainBreach(milk, new BigDecimal("6")));
        assertTrue(AcceptanceService.isColdChainBreach(milk, new BigDecimal("6.01")));
        assertTrue(AcceptanceService.isColdChainBreach(milk, new BigDecimal("-0.1")));
        assertFalse(AcceptanceService.isColdChainBreach(milk, new BigDecimal("4")));
        // 非冷链食材不判超标
        assertFalse(AcceptanceService.isColdChainBreach(egg, new BigDecimal("20")));
    }

    @Test
    void rejectCreatesReturnClaimSubstitutePurchaseAndMenuAdjustment() {
        newCase(ColdChainCaseStatus.ON_HOLD);
        stubTodayMenuShortage();
        Claim claim = new Claim();
        claim.setStatus(ClaimStatus.PENDING);
        when(claimService.createInternal(any(), any(), any(), anyString(), any(), anyString()))
                .thenReturn(claim);

        ColdChainCase result = service.decide(100L, "canteen",
                new ColdChainCaseService.DecideReq(ColdChainDisposition.REJECT, null, "酸奶温度超标"));

        // 退货 100L，货值 400
        verify(returnService).createInternal(eq(dairySupplier), eq(item), eq(milk),
                eq(new BigDecimal("100")), anyString(), eq(new BigDecimal("400.00")));
        // 自动索赔 = 货值 400 * 20% = 80，且直接核准进入结算
        ArgumentCaptor<BigDecimal> amountCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(claimService).createInternal(any(), any(), any(), anyString(), amountCap.capture(), anyString());
        assertEquals(new BigDecimal("80.00"), amountCap.getValue());
        assertEquals(ClaimStatus.APPROVED, claim.getStatus());
        assertNotNull(claim.getResolvedAt());
        // 受影响餐次生成菜单调整 + 家长公告
        verify(menuAdjustmentRepo).save(any(MenuAdjustment.class));
        verify(announcementRepo).save(any(ParentAnnouncement.class));
        // 缺口 60L 生成紧急替代采购（向非涉事供应商采购鸡蛋）
        ArgumentCaptor<SubstitutePurchase> spCap = ArgumentCaptor.forClass(SubstitutePurchase.class);
        verify(substitutePurchaseRepo).save(spCap.capture());
        SubstitutePurchase sp = spCap.getValue();
        assertEquals(altSupplier, sp.getSupplier());
        assertEquals(egg, sp.getIngredient());
        assertEquals(new BigDecimal("60"), sp.getQuantity());
        // 暂扣数量转入拒收，案件结案
        assertEquals(new BigDecimal("100"), item.getRejectedQty());
        assertEquals(BigDecimal.ZERO, item.getHeldQty());
        assertEquals(ColdChainCaseStatus.REJECTED, result.getStatus());
        assertNotNull(result.getClosedAt());
        verify(orderRepo).save(any(PurchaseOrder.class));
    }

    @Test
    void retestPassedReleasesStockAndWaivesPenalty() {
        ColdChainCase c = newCase(ColdChainCaseStatus.RETEST_PENDING);

        ColdChainCase result = service.retest(100L, "quality", RetestOutcome.PASSED,
                new BigDecimal("4.5"), "中心温度复测合格");

        // 正常入库 100L
        verify(inventoryService).change(eq(10L), eq(new BigDecimal("100")),
                eq(InventoryTxnType.IN), eq("ACCEPTANCE_RETEST"), eq(100L), anyString());
        // 冷链扣罚清零并关闭异常
        assertEquals(new BigDecimal("0.00"), anomaly.getPenaltyAmount());
        assertEquals(AnomalyStatus.RESOLVED, anomaly.getStatus());
        // 暂扣转为合格数量，案件结案
        assertEquals(new BigDecimal("100"), item.getAcceptedQty());
        assertEquals(ColdChainCaseStatus.ACCEPTED, result.getStatus());
    }

    @Test
    void retestFailedReturnsToCanteenDecision() {
        newCase(ColdChainCaseStatus.RETEST_PENDING);

        ColdChainCase result = service.retest(100L, "quality", RetestOutcome.FAILED,
                new BigDecimal("11"), "复测仍超标");

        assertEquals(ColdChainCaseStatus.RETEST_FAILED, result.getStatus());
        assertEquals(RetestOutcome.FAILED, result.getRetestOutcome());
        verify(inventoryService, never()).change(anyLong(), any(), any(), anyString(), anyLong(), anyString());
        // 复检失败后食堂可重新选择拒收
        stubTodayMenuShortage();
        when(claimService.createInternal(any(), any(), any(), anyString(), any(), anyString()))
                .thenAnswer(inv -> new Claim());
        ColdChainCase rejected = service.decide(100L, "canteen",
                new ColdChainCaseService.DecideReq(ColdChainDisposition.REJECT, null, null));
        assertEquals(ColdChainCaseStatus.REJECTED, rejected.getStatus());
    }

    @Test
    void downgradeNeedsBothApprovalAndQualityReviewBeforeRestrictedStockIn() {
        // 食堂选择降级
        ColdChainCase c = newCase(ColdChainCaseStatus.ON_HOLD);
        ColdChainCase pending = service.decide(100L, "canteen",
                new ColdChainCaseService.DecideReq(ColdChainDisposition.DOWNGRADE,
                        DowngradeUsageScope.STAFF_MEAL, "限教职工餐"));
        assertEquals(ColdChainCaseStatus.DOWNGRADE_PENDING, pending.getStatus());
        assertEquals(DowngradeUsageScope.STAFF_MEAL, pending.getUsageScope());
        verify(inventoryService, never()).change(anyLong(), any(), any(), anyString(), anyLong(), anyString());

        // 采购批准折价 8 折 -> 仍不能入库，等待质检复查
        when(caseRepo.findById(100L)).thenReturn(Optional.of(pending));
        ColdChainCase approved = service.approveDowngrade(100L, "canteen", true,
                new BigDecimal("0.20"), "同意 8 折");
        assertEquals(ColdChainCaseStatus.DOWNGRADE_APPROVED, approved.getStatus());
        verify(inventoryService, never()).change(anyLong(), any(), any(), anyString(), anyLong(), anyString());

        // 质检复查通过 -> 限定用途入库 + 受限批次登记
        when(caseRepo.findById(100L)).thenReturn(Optional.of(approved));
        ColdChainCase accepted = service.qualityReview(100L, "quality", true, "感官与中心温度复查合格");
        assertEquals(ColdChainCaseStatus.ACCEPTED, accepted.getStatus());
        verify(inventoryService).change(eq(10L), eq(new BigDecimal("100")),
                eq(InventoryTxnType.IN), eq("COLD_DOWNGRADE"), eq(100L), anyString());
        ArgumentCaptor<RestrictedStockBatch> rbCap = ArgumentCaptor.forClass(RestrictedStockBatch.class);
        verify(restrictedBatchRepo).save(rbCap.capture());
        RestrictedStockBatch rb = rbCap.getValue();
        assertEquals(new BigDecimal("100"), rb.getRemainingQty());
        assertEquals(DowngradeUsageScope.STAFF_MEAL, rb.getUsageScope());
        assertEquals(0, new BigDecimal("0.2000").compareTo(rb.getDiscountRate()));
        assertEquals(new BigDecimal("100"), item.getDowngradedQty());
        assertEquals(BigDecimal.ZERO, item.getHeldQty());
    }

    @Test
    void qualityReviewFailedTurnsIntoRejection() {
        ColdChainCase approved = newCase(ColdChainCaseStatus.DOWNGRADE_APPROVED);
        approved.setUsageScope(DowngradeUsageScope.NON_READY_TO_EAT);
        when(claimService.createInternal(any(), any(), any(), anyString(), any(), anyString()))
                .thenAnswer(inv -> new Claim());

        ColdChainCase result = service.qualityReview(100L, "quality", false, "复查仍有异味");

        assertEquals(ColdChainCaseStatus.REJECTED, result.getStatus());
        assertEquals(new BigDecimal("100"), item.getRejectedQty());
        verify(returnService).createInternal(any(), any(), any(), eq(new BigDecimal("100")), anyString(), any());
    }

    @Test
    void downgradeWithoutUsageScopeRejected() {
        newCase(ColdChainCaseStatus.ON_HOLD);
        assertThrows(BusinessException.class, () -> service.decide(100L, "canteen",
                new ColdChainCaseService.DecideReq(ColdChainDisposition.DOWNGRADE, null, null)));
    }
}
