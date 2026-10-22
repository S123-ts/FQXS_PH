package org.example.fqxs.repository;

import org.example.fqxs.entity.AiEngine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * AI 引擎注册表。
 */
public interface AiEngineRepository extends JpaRepository<AiEngine, String> {

    /** 按熔断/展示顺序取全部引擎。 */
    List<AiEngine> findAllByOrderByPriorityAsc();
}
