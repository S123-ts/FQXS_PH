package org.example.fqxs.service;

import org.example.fqxs.config.AiDetectConfig;

import java.util.Map;
import java.util.Set;

/**
 * 内置免费型号目录：只用于界面标注，不参与判定门禁。
 *
 * <p>名单会随平台调整（硅基流动明确说过免费名单会变），以控制台为准。
 * 不在名单里的型号一律不标「免费」——宁可不标，也不能把付费型号标成免费。</p>
 */
public final class FreeModelCatalog {

    private static final Map<String, Set<String>> FREE_MODELS = Map.of(
            AiDetectConfig.ENGINE_GLM, Set.of(
                    "glm-4-flash", "glm-4-flash-250414", "glm-4.7-flash", "glm-4.5-flash"),
            // 硅基流动的清单里 GLM-4-9B 带 THUDM/ 组织前缀，写成裸名 "GLM-4-9B-0414"
            // 会被上游以 400 "Model does not exist" 拒掉：标了免费却调不通，等于诱导用户勾一个坏型号
            AiDetectConfig.ENGINE_QWEN, Set.of(
                    "Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen3-8B", "THUDM/GLM-4-9B-0414"));

    private FreeModelCatalog() {
    }

    /**
     * 该引擎的这个型号是否内置免费。
     *
     * @param engine 引擎标识
     * @param model  型号
     * @return true 表示已知免费
     */
    public static boolean isFree(String engine, String model) {
        return FREE_MODELS.getOrDefault(engine, Set.of()).contains(model);
    }
}
