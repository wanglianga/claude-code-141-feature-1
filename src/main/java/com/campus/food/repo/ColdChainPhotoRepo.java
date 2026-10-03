package com.campus.food.repo;

import com.campus.food.domain.ColdChainPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ColdChainPhotoRepo extends JpaRepository<ColdChainPhoto, Long> {
    List<ColdChainPhoto> findByColdChainCaseId(Long coldChainCaseId);
}
