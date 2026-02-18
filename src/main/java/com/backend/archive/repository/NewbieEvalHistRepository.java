package com.backend.archive.repository;

import com.backend.archive.entity.NewbieEvalHist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NewbieEvalHistRepository extends JpaRepository<NewbieEvalHist, Long> {

    List<NewbieEvalHist> findByEvalUserNoAndNewbieUserNoOrderByEvalNoDesc(Long evalUserNo, Long newbieUserNo);
}
