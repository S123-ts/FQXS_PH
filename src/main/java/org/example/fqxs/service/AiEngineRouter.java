package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 免费判定引擎的路由：把「请求里的引擎」翻译成「这一次真正调用的引擎」。
 *
 * <p>两种取值：</p>
 * <ol>
 *   <li><b>显式指定</b>：按指定引擎执行；不可用（未配 Key/停用/不存在）直接报错，不静默换引擎。</li>
 *   <li><b>随机通道</b>：请求为空或 {@code random} 时，从已配 Key 的免费引擎里随机挑，
 *       并优先避开「这本书已经判过的引擎」，避免同一本书被两份额度各判一遍。</li>
 * </ol>
 *
 * <p>落库的 engine 永远是真实引擎：{@code random} 只是路由指令，不进明细表。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiEngineRouter {

    /** 随机路由的请求值。 */
    public static final String RANDOM = "random";

    private final AiEngineRegistry engineRegistry;

    /**
     * 解析本次实际调用的引擎。
     *
     * @param requested 请求引擎；null / 空 / random 表示随机
     * @return 真实引擎标识
     * @throws IllegalArgumentException 没有可用引擎，或指定引擎不可用
     */
    public String resolve(String requested) {
        return resolveAvoiding(requested, List.of());
    }

    /**
     * 手点徽章重跑的路由：引擎仅因连续失败被熔断时放行，其余不可用情形照旧报错。
     *
     * <p>熔断是引擎级粗粒度：往往只是一个型号连续 429 就把整个引擎摁住，而徽章重跑
     * 会在勾选的型号池内轮换（见 {@code AiDetectServiceImpl} 的型号避让）。用户主动重试
     * 是明确意图，不该被「上一个型号刷出来的熔断」挡在门口；真坏了还会再次失败留证续期。</p>
     *
     * @param requested 请求引擎；null / 空 / random 表示随机
     * @return 真实引擎标识
     * @throws IllegalArgumentException 没有可用引擎，或指定引擎根本没配好（未配 Key/停启用关/不存在）
     */
    public String resolveForRerun(String requested) {
        if (isRandom(requested)) {
            return resolve(requested);
        }
        AiEngine record = engineRegistry.find(requested).orElse(null);
        if (record != null && record.isSuspended() && record.isEnabled()
                && record.isOpenAiCompatible() && engineRegistry.hasKey(requested)) {
            log.info("重跑放行停用中的引擎: {}（停用原因：{}，至 {}），将在型号池内轮换",
                    requested, record.getDisableReason(), record.getDisableUntil());
            return requested;
        }
        return resolveAvoiding(requested, List.of());
    }

    /**
     * 解析引擎，随机模式优先避开 excluded 里的引擎。
     *
     * @param requested 请求引擎
     * @param excluded  该书已判过的引擎，可为 null
     * @return 真实引擎标识
     */
    public String resolveAvoiding(String requested, Collection<String> excluded) {
        List<String> available = engineRegistry.availableFreeEngines();
        if (available.isEmpty()) {
            throw new IllegalArgumentException("没有可用的免费引擎：请先在「API 密钥」面板配置 Key");
        }
        if (!isRandom(requested)) {
            if (!available.contains(requested)) {
                // 自动停用要说清"到什么时候、为什么、怎么解除"：只报"不可用"的话，
                // 用户既不知道它是刚被连续失败摁掉的，也找不到解除入口
                AiEngine record = engineRegistry.find(requested).orElse(null);
                if (record != null && record.isSuspended()) {
                    throw new IllegalArgumentException("引擎 " + requested + " 已自动停用至 "
                            + record.getDisableUntil() + "，原因：" + record.getDisableReason()
                            + "；可在「失败日志」页立即解除");
                }
                throw new IllegalArgumentException("引擎 " + requested + " 不可用（未配置 Key、已停用或不存在）"
                        + "，当前可用：" + String.join(" / ", available));
            }
            return requested;
        }
        List<String> candidates = freshCandidates(available, excluded);
        String picked = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        log.debug("随机路由选中引擎: {}（候选 {}，已排除 {}）", picked, candidates, excluded);
        return picked;
    }

    /** 随机是否真的能分摊：至少两个可用引擎。 */
    public boolean randomAvailable() {
        return engineRegistry.availableFreeEngines().size() >= 2;
    }

    /** 请求值是否表示随机。 */
    public static boolean isRandom(String engine) {
        return engine == null || engine.isBlank() || RANDOM.equalsIgnoreCase(engine);
    }

    private List<String> freshCandidates(List<String> available, Collection<String> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return new ArrayList<>(available);
        }
        List<String> fresh = new ArrayList<>();
        for (String engine : available) {
            if (!excluded.contains(engine)) {
                fresh.add(engine);
            }
        }
        // 全都判过：允许重复判，让「重判」仍然可行
        return fresh.isEmpty() ? new ArrayList<>(available) : fresh;
    }
}
