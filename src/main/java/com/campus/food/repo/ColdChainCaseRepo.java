package com.campus.food.repo;

import com.campus.food.domain.ColdChainCase;
import com.campus.food.domain.enums.ColdChainDisposition;
import com.campus.food.domain.enums.ColdChainReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ColdChainCaseRepo extends JpaRepository<ColdChainCase, Long> {
    boolean existsByDeliveryItemId(Long deliveryItemId);
    Optional<ColdChainCase> findByDeliveryItemId(Long deliveryItemId);
    List<ColdChainCase> findByBatchId(Long batchId);
    List<ColdChainCase> findByDisposition(ColdChainDisposition disposition);
    List<ColdChainCase> findByReviewStatus(ColdChainReviewStatus reviewStatus);

    @Query("select c from ColdChainCase c where c.batch.order.supplier.id = :supplierId")
    List<ColdChainCase> findBySupplierId(@Param("supplierId") Long supplierId);

    @Query("select c from ColdChainCase c where c.batch.order.supplier.id = :supplierId " +
           "and c.createdAt between :from and :to")
    List<ColdChainCase> findBySupplierIdAndCreatedAtBetween(@Param("supplierId") Long supplierId,
                                                            @Param("from") LocalDateTime from,
                                                            @Param("to") LocalDateTime to);
}
