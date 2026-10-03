package com.campus.food.repo;

import com.campus.food.domain.SubstitutePurchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubstitutePurchaseRepo extends JpaRepository<SubstitutePurchase, Long> {

    List<SubstitutePurchase> findByColdChainCaseId(Long coldChainCaseId);

    List<SubstitutePurchase> findBySupplierId(Long supplierId);
}
