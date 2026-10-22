package org.example.fqxs.model;

/**
 * 单次 AI 判定的结论。
 *
 * @param aiProbability AI 生成概率，0–100
 * @param engine        产出该结论的引擎标识（glm-4-flash / zhuque-text）
 * @param reason        判定依据摘要，用于人工复核；朱雀不给理由时为空串
 * @param promptId      本次判定使用的提示词变体 id（朱雀不经过提示词，为 null）
 */
public record AiVerdict(double aiProbability, String engine, String reason, String promptId) {

    /** 不经过提示词通道的结论（朱雀）与测试夹具的便捷构造。 */
    public AiVerdict(double aiProbability, String engine, String reason) {
        this(aiProbability, engine, reason, null);
    }
}
