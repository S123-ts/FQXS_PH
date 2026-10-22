package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiPrompt;
import org.example.fqxs.repository.AiPromptRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 判定提示词注册表：把提示词从代码常量搬进 {@code ai_prompt} 表，判定时按策略取一条。
 *
 * <p>两种取值：</p>
 * <ol>
 *   <li><b>显式指定</b>：按 id 取；不存在、已停用直接报错，不静默换提示词——
 *       否则拿到的分数无法追溯出自哪个变体。</li>
 *   <li><b>随机</b>：请求为空或 {@code random} 时，从启用池里按权重随机挑一条。
 *       权重让更稳的变体多承担一些量，同时保证每个变体都有出场机会。</li>
 * </ol>
 *
 * <p>库为空（首次启动早于播种、或用户清空了表）时退回内存内置版，
 * 判定链路不会因为库空而全线失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPromptRegistry {

    private final AiPromptRepository repository;
    private final AiDetectConfig aiDetectConfig;

    /** 全部变体，按展示顺序。 */
    public List<AiPrompt> all() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    public Optional<AiPrompt> find(String id) {
        return id == null ? Optional.empty() : repository.findById(id);
    }

    /**
     * 取变体，不存在直接报错。
     *
     * @param id 变体标识
     * @return 记录
     * @throws IllegalArgumentException 未知变体
     */
    public AiPrompt require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("未知提示词变体: " + id));
    }

    /** 随机池：启用且允许随机的变体。 */
    public List<AiPrompt> pool() {
        return repository.findByEnabledTrueAndPoolableTrueOrderBySortOrderAsc();
    }

    /**
     * 按策略取出本次判定要用的提示词。
     *
     * <p>随机模式下每次调用都会重新掷一次，因此同一批书会摊到不同变体上；
     * 具体用了哪个会随结论落进 {@code novel_ai_verdict.prompt_id}，事后可比对各变体分歧。</p>
     *
     * @param requested 请求变体；null / 空 / random 表示随机
     * @return 提示词实体（含 id，供落库追溯）
     * @throws IllegalArgumentException 显式指定的变体不可用
     */
    public AiPrompt pick(String requested) {
        if (!AiPromptTemplates.RANDOM.equals(requested) && requested != null && !requested.isBlank()) {
            AiPrompt record = find(requested.trim())
                    .orElseThrow(() -> new IllegalArgumentException("未知提示词变体: " + requested));
            if (!record.isEnabled()) {
                throw new IllegalArgumentException("提示词变体 " + requested + " 已停用");
            }
            return record;
        }
        if (!isRandomStrategy()) {
            return fixedOrDefault();
        }
        List<AiPrompt> candidates = pool();
        if (candidates.isEmpty()) {
            // 池空（未播种或全停用）：先按配置的默认变体，再退回内置版，不让整批判定失败
            log.warn("提示词池为空，本次判定退回默认变体");
            return fixedOrDefault();
        }
        return weightedRandom(candidates);
    }

    /** 面板用：变体清单与是否可随机。 */
    public List<AiPrompt> describeAll() {
        List<AiPrompt> db = all();
        return db.isEmpty() ? AiPromptTemplates.builtins() : db;
    }

    /** 随机是否真的能分摊：池里至少两个变体。 */
    public boolean randomAvailable() {
        return pool().size() >= 2;
    }

    /** 配置是否为随机策略。 */
    public boolean isRandomStrategy() {
        String strategy = aiDetectConfig.getPromptStrategy();
        return strategy == null || strategy.isBlank() || "random".equalsIgnoreCase(strategy.trim());
    }

    /**
     * 按权重随机：累积权重后掷一次，权重越大中签率越高。
     * 权重非正数的变体直接跳过，避免被停用项拖成必不中。
     */
    private AiPrompt weightedRandom(List<AiPrompt> candidates) {
        List<AiPrompt> usable = new ArrayList<>();
        int total = 0;
        for (AiPrompt candidate : candidates) {
            int weight = candidate.getWeight() > 0 ? candidate.getWeight() : 0;
            if (weight <= 0) {
                continue;
            }
            usable.add(candidate);
            total += weight;
        }
        if (usable.isEmpty()) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
        int ticket = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (AiPrompt candidate : usable) {
            cumulative += candidate.getWeight();
            if (ticket < cumulative) {
                log.debug("随机提示词命中: {}（权重 {}）", candidate.getId(), candidate.getWeight());
                return candidate;
            }
        }
        return usable.get(usable.size() - 1);
    }

    /** 固定策略：取配置的默认变体，取不到就用内置兜底。 */
    private AiPrompt fixedOrDefault() {
        String defaultId = aiDetectConfig.getDefaultPrompt();
        if (defaultId != null && !defaultId.isBlank()) {
            Optional<AiPrompt> record = find(defaultId.trim());
            if (record.isPresent() && record.get().isEnabled()) {
                return record.get();
            }
            log.warn("配置的默认提示词 {} 不可用，退回内置默认变体", defaultId);
        }
        return AiPromptTemplates.defaultPrompt();
    }
}
