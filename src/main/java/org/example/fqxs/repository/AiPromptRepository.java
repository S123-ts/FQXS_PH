package org.example.fqxs.repository;

import org.example.fqxs.entity.AiPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * AI 判定提示词库。
 */
public interface AiPromptRepository extends JpaRepository<AiPrompt, String> {

    /** 按展示顺序取全部变体。 */
    List<AiPrompt> findAllByOrderBySortOrderAsc();

    /** 随机池：启用且允许随机的变体。 */
    List<AiPrompt> findByEnabledTrueAndPoolableTrueOrderBySortOrderAsc();
}
