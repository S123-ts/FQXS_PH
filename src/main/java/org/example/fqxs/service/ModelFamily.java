package org.example.fqxs.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 型号家族归一：把「同一个模型的不同包装」压成一个代表型号。
 *
 * <p><b>为什么需要</b>：免费额度按调用次数算，而型号池里常见的差异只是上下文长度或日期快照
 * （{@code ernie-4.5-turbo-32k} / {@code ernie-4.5-turbo-128k}、{@code glm-4-flash} / {@code glm-4}），
 * 判定结果几乎不可能有区别，各跑一遍等于把额度白扔一半。后台逐章校验因此只在<b>家族代表</b>上跑
 * （{@link AiEngineRegistry#distinctModelsOf}），手动判定仍用全池随机——那是用户显式点的，
 * 摊到多个型号上反而能绕过单型号的 RPM 限流。</p>
 *
 * <p><b>归一规则</b>：型号名按 {@code -} 拆段、统一小写，从末尾往前剥掉三类「包装段」，
 * 剩下的主干就是家族键：</p>
 * <ul>
 *   <li>上下文长度：{@code -32k} / {@code -128k}</li>
 *   <li>日期快照：结尾的 4–8 位纯数字（{@code -0414} / {@code -250414}）</li>
 *   <li>变体词：{@code -flash} / {@code -turbo} / {@code -plus} / {@code -air} / {@code -max}
 *       / {@code -lite} / {@code -instruct} / {@code -chat} / {@code -base}</li>
 * </ul>
 *
 * <p><b>刻意不剥</b>的：版本号（{@code glm-4} vs {@code glm-5.3}）与参数规模（{@code -7b} / {@code -9b}）——
 * 那是真正不同的模型，合并会漏判。所以 {@code Qwen2.5-7B-Instruct} 与 {@code Qwen3-8B} 仍是两个家族。</p>
 *
 * <p>规则不认识的新后缀只会「少合并」（各跑一遍，多花额度），不会「多合并」（漏掉一个模型），
 * 这是刻意的失败方向。</p>
 */
public final class ModelFamily {

    /** 上下文长度段：{@code 32k} / {@code 128k}。 */
    private static final Pattern CONTEXT_SEGMENT = Pattern.compile("\\d+k");

    /** 日期快照段：结尾的 4–8 位纯数字（{@code 0414}、{@code 250414}）。 */
    private static final Pattern DATE_SEGMENT = Pattern.compile("\\d{4,8}");

    /** 变体词段：同一模型的不同包装，判定结果没有区别。 */
    private static final Set<String> VARIANT_SEGMENTS = Set.of(
            "flash", "turbo", "plus", "air", "max", "lite", "instruct", "chat", "base");

    private ModelFamily() {
    }

    /**
     * 型号 → 家族键。同一模型的上下文变体、日期快照与变体词都会归到同一个键。
     *
     * @param model 型号名（可含 {@code provider/} 前缀与 {@code #verify} 这类内部后缀）
     * @return 家族键；入参为空白时原样返回（不猜、不抛）
     */
    public static String keyOf(String model) {
        if (model == null) {
            return "";
        }
        String raw = model.trim();
        if (raw.isEmpty()) {
            return "";
        }
        // 内部行标识（model#verify）不参与家族判定：先切掉，否则每段都带上后缀、永远归不到一起
        int marker = raw.indexOf('#');
        if (marker >= 0) {
            raw = raw.substring(0, marker);
        }
        String[] segments = raw.toLowerCase(Locale.ROOT).split("-");
        int end = segments.length;
        while (end > 1 && isWrapping(segments[end - 1])) {
            end--;
        }
        return String.join("-", java.util.Arrays.copyOfRange(segments, 0, end));
    }

    /**
     * 家族去重：每个家族保留<b>池内最先出现</b>的那个型号作代表。
     *
     * <p>保序是有意的——型号池的顺序就是用户在面板里勾的顺序，代表型号取第一个，
     * 换池时不会因为 {@link java.util.HashSet} 的遍历顺序漂移而让游标认错型号。</p>
     *
     * @param models 型号池（{@link AiEngineRegistry#modelsOf} 的结果，非空）
     * @return 去重后的型号列表，非空
     */
    public static List<String> distinct(List<String> models) {
        Map<String, String> representative = new LinkedHashMap<>();
        for (String model : models) {
            if (model == null || model.isBlank()) {
                continue;
            }
            representative.putIfAbsent(keyOf(model), model);
        }
        return new ArrayList<>(representative.values());
    }

    private static boolean isWrapping(String segment) {
        return segment.isEmpty()
                || CONTEXT_SEGMENT.matcher(segment).matches()
                || DATE_SEGMENT.matcher(segment).matches()
                || VARIANT_SEGMENTS.contains(segment);
    }
}
