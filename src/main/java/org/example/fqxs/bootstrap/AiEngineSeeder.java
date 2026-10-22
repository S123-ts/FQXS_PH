package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.repository.AiEngineRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 引擎注册表的首次播种：只插入缺失的内置引擎，不覆盖用户改过的型号/端点。
 *
 * <p>内置三行（GLM / 千帆 / 硅基流动）的初始型号取 {@link AiDetectConfig} 里
 * 由 application.yml 或 properties 迁移得到的值，避免升级后把用户选过的型号重置。
 * 播种后把历史判定行的 {@code model=legacy} 归属到各引擎当前型号，
 * 否则升级完第一次初筛会把全库重判一遍。</p>
 */
@Slf4j
@Component
@Order(101) // 晚于 SchemaInitializer(建表) 与 StartupDataInitializer
@RequiredArgsConstructor
public class AiEngineSeeder implements ApplicationRunner {

    private static final String GLM_MODELS =
            "glm-4-flash,glm-4.7-flash,glm-4-flash-250414,glm-4.5-flash,glm-4.7,glm-5.3-flash";
    // 2026-10-22 实测：ERNIE-Speed 系列已不在千帆在线服务清单，出厂清单改放实测 200 的型号
    private static final String QIANFAN_MODELS =
            "ernie-4.5-turbo-32k,ernie-4.5-turbo-128k,deepseek-v4-flash-0731,glm-5.3-flash";
    private static final String QWEN_MODELS =
            "Qwen/Qwen2.5-7B-Instruct,Qwen/Qwen3-8B,Qwen/Qwen2.5-14B-Instruct,GLM-4-9B-0414";

    private final AiEngineRepository engineRepository;
    private final NovelAiVerdictRepository verdictRepository;
    private final AiDetectConfig aiDetectConfig;

    @Override
    public void run(ApplicationArguments args) {
        seedBuiltins();
        backfillLegacyModels();
    }

    private void seedBuiltins() {
        List<AiEngine> builtins = List.of(
                AiEngine.builder()
                        .id(AiDetectConfig.ENGINE_GLM)
                        .name("智谱 GLM")
                        .baseUrl(aiDetectConfig.getGlmUrl())
                        .model(aiDetectConfig.getGlmModel())
                        .modelsCsv(GLM_MODELS)
                        .protocol("openai_chat")
                        .builtin(true).enabled(true).autoBan(true).priority(0)
                        .remark("Flash 系列有免费档；付费型号按量计费")
                        .build(),
                AiEngine.builder()
                        .id(AiDetectConfig.ENGINE_QIANFAN)
                        .name("百度千帆 ERNIE-Speed")
                        .baseUrl(aiDetectConfig.getQianfanUrl())
                        .model(aiDetectConfig.getQianfanModel())
                        .modelsCsv(QIANFAN_MODELS)
                        .protocol("openai_chat")
                        .builtin(true).enabled(true).autoBan(true).priority(10)
                        .remark("需先在千帆控制台开通在线服务；无零价型号，赠送额度型按量计费")
                        .build(),
                AiEngine.builder()
                        .id(AiDetectConfig.ENGINE_QWEN)
                        .name("硅基流动 Qwen")
                        .baseUrl(aiDetectConfig.getQwenUrl())
                        .model(aiDetectConfig.getQwenModel())
                        .modelsCsv(QWEN_MODELS)
                        .protocol("openai_chat")
                        .builtin(true).enabled(true).autoBan(true).priority(20)
                        .remark("9B 以下多数永久免费，名单以平台为准")
                        .build());

        int inserted = 0;
        for (AiEngine engine : builtins) {
            if (engineRepository.existsById(engine.getId())) {
                continue;
            }
            engineRepository.save(engine);
            inserted++;
        }
        if (inserted > 0) {
            log.info("AI 引擎注册表已播种 {} 个内置引擎", inserted);
        }
    }

    private void backfillLegacyModels() {
        for (AiEngine engine : engineRepository.findAll()) {
            // 历史判定行的 model=legacy 要归属到当前型号，否则升级完第一次初筛会把全库重判一遍
            if (engine.getModel() == null || engine.getModel().isBlank()) {
                continue;
            }
            int updated = verdictRepository.backfillModel(engine.getId(), engine.getModel());
            if (updated > 0) {
                log.info("历史判定行已归属型号: engine={}, model={}, {} 行", engine.getId(), engine.getModel(), updated);
            }
        }
    }
}
