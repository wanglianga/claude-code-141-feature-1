package com.campus.food.repo;

import com.campus.food.domain.RestrictedStockBatch;
import com.campus.food.domain.enums.RestrictedBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestrictedStockBatchRepo extends JpaRepository<RestrictedStockBatch, Long> {

    List<RestrictedStockBatch> findByIngredientId(Long ingredientId);

    List<RestrictedStockBatch> findByIngredientIdAndStatus(Long ingredientId, RestrictedBatchStatus status);
}
