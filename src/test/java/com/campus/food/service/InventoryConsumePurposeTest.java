package com.campus.food.service;

import com.campus.food.domain.Ingredient;
import com.campus.food.domain.RestrictedStockBatch;
import com.campus.food.domain.StockLevel;
import com.campus.food.domain.enums.ConsumePurpose;
import com.campus.food.domain.enums.DowngradeUsageScope;
import com.campus.food.domain.enums.RestrictedBatchStatus;
import com.campus.food.repo.IngredientRepo;
import com.campus.food.repo.InventoryTxnRepo;
import com.campus.food.repo.RestrictedStockBatchRepo;
import com.campus.food.repo.StockLevelRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.*;

/** 冷链降级受限库存的用途管制：学生餐禁用，教职工餐/非即食加工优先消耗匹配批次 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryConsumePurposeTest {

    @Mock StockLevelRepo stockLevelRepo;
    @Mock InventoryTxnRepo txnRepo;
    @Mock IngredientRepo ingredientRepo;
    @Mock RestrictedStockBatchRepo restrictedBatchRepo;

    @InjectMocks InventoryService inventoryService;

    private Ingredient milk;
    private StockLevel level;

    @BeforeEach
    void setUp() {
        milk = new Ingredient();
        milk.setId(1L);
        milk.setName("鲜牛奶");
        milk.setUnit("L");
        when(ingredientRepo.findById(1L)).thenReturn(Optional.of(milk));

        level = new StockLevel();
        level.setIngredient(milk);
        when(stockLevelRepo.findByIngredientId(1L)).thenReturn(Optional.of(level));
    }

    private RestrictedStockBatch restrictedBatch(long id, DowngradeUsageScope scope, String remaining) {
        RestrictedStockBatch rb = new RestrictedStockBatch();
        rb.setId(id);
        rb.setIngredient(milk);
        rb.setQuantity(new BigDecimal("100"));
        rb.setRemainingQty(new BigDecimal(remaining));
        rb.setUsageScope(scope);
        rb.setStatus(RestrictedBatchStatus.AVAILABLE);
        return rb;
    }

    @Test
    void studentMealCannotConsumeRestrictedStock() {
        // 总库存 100，其中 60 为教职工餐受限批次，合格库存仅 40
        level.setQuantity(new BigDecimal("100"));
        RestrictedStockBatch rb = restrictedBatch(1L, DowngradeUsageScope.STAFF_MEAL, "60");
        when(restrictedBatchRepo.findByIngredientIdAndStatus(1L, RestrictedBatchStatus.AVAILABLE))
                .thenReturn(List.of(rb));

        // 学生餐要领 50，超过合格库存 40 -> 拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.consumeFor(1L, new BigDecimal("50"),
                        ConsumePurpose.STUDENT_MEAL, "学生早餐"));
        assertTrue(ex.getMessage().contains("学生餐只能使用合格非受限库存"));
        verify(stockLevelRepo, never()).save(any());
        verify(restrictedBatchRepo, never()).save(any());

        // 学生餐领 40 正常出库，受限批次不动
        inventoryService.consumeFor(1L, new BigDecimal("40"), ConsumePurpose.STUDENT_MEAL, null);
        assertEquals(new BigDecimal("60"), level.getQuantity());
        assertEquals(new BigDecimal("60"), rb.getRemainingQty());
    }

    @Test
    void staffMealConsumesMatchingRestrictedBatchFirst() {
        // 总库存 100 = staff 受限 60 + 合格 40
        level.setQuantity(new BigDecimal("100"));
        RestrictedStockBatch staffBatch = restrictedBatch(1L, DowngradeUsageScope.STAFF_MEAL, "60");
        when(restrictedBatchRepo.findByIngredientIdAndStatus(1L, RestrictedBatchStatus.AVAILABLE))
                .thenReturn(List.of(staffBatch));

        // 教职工餐领 80：先消耗 staff 批次 60，余 20 走合格库存
        inventoryService.consumeFor(1L, new BigDecimal("80"), ConsumePurpose.STAFF_MEAL, "教职工午餐");
        assertEquals(new BigDecimal("20"), level.getQuantity());
        assertEquals(new BigDecimal("0"), staffBatch.getRemainingQty());
        assertEquals(RestrictedBatchStatus.USED_UP, staffBatch.getStatus());
    }

    @Test
    void nonReadyProcessingDoesNotConsumeStaffScopedBatch() {
        // 总库存 100 = staff 受限 60 + 非即食受限 30 + 合格 10
        level.setQuantity(new BigDecimal("100"));
        RestrictedStockBatch staffBatch = restrictedBatch(1L, DowngradeUsageScope.STAFF_MEAL, "60");
        RestrictedStockBatch nonReadyBatch = restrictedBatch(2L, DowngradeUsageScope.NON_READY_TO_EAT, "30");
        when(restrictedBatchRepo.findByIngredientIdAndStatus(1L, RestrictedBatchStatus.AVAILABLE))
                .thenReturn(List.of(staffBatch, nonReadyBatch));

        // 非即食加工领 50：消耗非即食批次 30 + 合格库存 20（合格仅 10，不足）-> 拒绝
        assertThrows(BusinessException.class, () -> inventoryService.consumeFor(1L,
                new BigDecimal("50"), ConsumePurpose.NON_READY_TO_EAT, "面点加工"));

        // 领 40：非即食 30 + 合格 10，放行
        inventoryService.consumeFor(1L, new BigDecimal("40"),
                ConsumePurpose.NON_READY_TO_EAT, null);
        assertEquals(new BigDecimal("60"), level.getQuantity());
        assertEquals(new BigDecimal("0"), nonReadyBatch.getRemainingQty());
        assertEquals(new BigDecimal("60"), staffBatch.getRemainingQty());
    }
}
