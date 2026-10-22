package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.entity.AiPrompt;
import org.example.fqxs.repository.AiPromptRepository;
import org.example.fqxs.service.AiPromptTemplates;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 判定提示词库的首次播种：只插入缺失的内置变体，不覆盖用户改过的权重或正文。
 *
 * <p>提示词此前写死在 {@code AiDetectClient} 的常量里，改一个字要重新编译；
 * 入库后可在面板调整权重与启停，随机池也随之变化。</p>
 */
@Slf4j
@Component
@Order(102) // 晚于 SchemaInitializer(建表) 与 AiEngineSeeder(101)
@RequiredArgsConstructor
public class AiPromptSeeder implements ApplicationRunner {

    private final AiPromptRepository promptRepository;

    @Override
    public void run(ApplicationArguments args) {
        int inserted = 0;
        for (AiPrompt template : AiPromptTemplates.builtins()) {
            if (promptRepository.existsById(template.getId())) {
                continue;
            }
            promptRepository.save(template);
            inserted++;
        }
        if (inserted > 0) {
            log.info("AI 提示词库已播种 {} 个内置变体（随机池 {} 个）", inserted,
                    AiPromptTemplates.builtins().stream().filter(AiPrompt::isPoolable).count());
        }
    }
}
