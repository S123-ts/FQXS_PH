package org.example.fqxs.repository;

import org.example.fqxs.entity.AiEngineFault;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/** 失败留证的查询入口：日志页面按时间窗口与引擎过滤。 */
public interface AiEngineFaultRepository extends JpaRepository<AiEngineFault, Long> {

    List<AiEngineFault> findByOccurredTimeAfterOrderByOccurredTimeDesc(
            LocalDateTime from, Pageable pageable);

    List<AiEngineFault> findByEngineAndOccurredTimeAfterOrderByOccurredTimeDesc(
            String engine, LocalDateTime from, Pageable pageable);

    long countByOccurredTimeAfter(LocalDateTime from);

    long countByEngineAndOccurredTimeAfter(String engine, LocalDateTime from);

    long countByKindAndOccurredTimeAfter(String kind, LocalDateTime from);
}
