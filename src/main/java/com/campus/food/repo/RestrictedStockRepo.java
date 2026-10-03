package com.campus.food.repo;

import com.campus.food.domain.RestrictedStock;
import com.campus.food.domain.enums.InventoryUsageScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface RestrictedStockRepo extends JpaRepository<RestrictedStock, Long> {
    List<RestrictedStock> findByIngredientId(Long ingredientId);
    java.util.Optional<RestrictedStock> findByColdChainCaseId(Long coldChainCaseId);

    @Query("select coalesce(sum(rs.quantity - rs.consumedQty), 0) from RestrictedStock rs " +
           "where rs.ingredient.id = :ingredientId and rs.usageScope = :scope")
    BigDecimal sumAvailable(@Param("ingredientId") Long ingredientId,
                            @Param("scope") InventoryUsageScope scope);
}
