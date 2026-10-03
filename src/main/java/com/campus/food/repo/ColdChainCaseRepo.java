package com.campus.food.repo;

import com.campus.food.domain.ColdChainCase;
import com.campus.food.domain.enums.ColdChainCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ColdChainCaseRepo extends JpaRepository<ColdChainCase, Long> {

    Optional<ColdChainCase> findByAcceptanceItemId(Long acceptanceItemId);

    List<ColdChainCase> findByBatchId(Long batchId);

    List<ColdChainCase> findBySupplierId(Long supplierId);

    List<ColdChainCase> findByStatus(ColdChainCaseStatus status);

    long countByAnomalyId(Long anomalyId);
}
