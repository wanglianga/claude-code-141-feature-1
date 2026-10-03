package com.campus.food.repo;

import com.campus.food.domain.MenuAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MenuAdjustmentRepo extends JpaRepository<MenuAdjustment, Long> {
    List<MenuAdjustment> findByColdChainCaseId(Long coldChainCaseId);
    List<MenuAdjustment> findByMealId(Long mealId);
}
