package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.service.NovelService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动回填：主表 AI 综合结论改成「按型号聚合」后，把历史行重算一遍。
 *
 * <p>口径变更只影响写入路径，旧行不会自动变；重算是幂等的，数据库不可用时跳过、
 * 下次启动再试，不给启动流程添风险（同 {@link StartupDataInitializer} 的契约）。</p>
 */
@Slf4j
@Component
@Order(101)
@RequiredArgsConstructor
public class AiAggregateBackfillRunner implements ApplicationRunner {

    private final NovelService novelService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int updated = novelService.recomputeAiAggregates();
            log.info("AI 综合结论回填完成: {} 本按型号重算", updated);
        } catch (RuntimeException failure) {
            log.error("AI 综合结论回填失败（数据库不可用？），跳过，下次启动重试: {}", failure.getMessage());
        }
    }
}
