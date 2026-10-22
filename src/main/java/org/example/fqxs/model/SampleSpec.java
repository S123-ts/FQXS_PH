package org.example.fqxs.model;

/**
 * 取样规则：从全书的哪些位置、用哪一路数据源抽章节送判。
 *
 * <p>2026-10-22 起只有三档，都是<b>固定章数、固定数据源</b>，界面不再让人填章数：</p>
 * <table border="1">
 *   <caption>三档取样</caption>
 *   <tr><th>规则</th><th>章号</th><th>数据源</th><th>是否落库</th></tr>
 *   <tr><td>{@link #HEAD}（默认）</td><td>全书前 10 章</td><td>官网 SSR</td><td>是</td></tr>
 *   <tr><td>{@link #RECENT}</td><td>全书最后 10 章</td><td>第三方会员通道</td><td>否</td></tr>
 *   <tr><td>{@link #RANDOM}</td><td>第 11 章起随机两段，每段 5 章</td><td>第三方会员通道</td><td>否</td></tr>
 * </table>
 *
 * <p>{@link #MID} 与 {@link #MID_RECENT} 是历史档位：保留解析只为旧链接不报错，
 * 按原来的「可读区中心 / 可读区末尾」跑，界面不再提供。</p>
 *
 * <p>策略非法时回落默认而不是报错——这是入口参数，报错会把整批初筛卡死。</p>
 *
 * @param strategy 取样策略标识
 * @param count    章数：head/recent 是总章数；random 是<b>每段</b>章数（段数固定 2）
 */
public record SampleSpec(String strategy, int count) {

    /** 历史默认策略：配置文件的中段 + 最新段。保留以兼容旧链接与显式传参。 */
    public static final String MID_RECENT = "mid_recent";

    /** 前 N 章：全书开头。默认档，走官网并落库。 */
    public static final String HEAD = "head";

    /** 中段 N 章：历史档位，按可读区中心算。 */
    public static final String MID = "mid";

    /** 最新 N 章：全书末尾，走第三方会员通道。 */
    public static final String RECENT = "recent";

    /** 随机两段：第 11 章起抽 2 段，每段 {@link #RANDOM_SEGMENT_CHAPTERS} 章，走第三方。 */
    public static final String RANDOM = "random";

    /** 默认档抽取章数：前 10 章，与未登录可读区一致。 */
    public static final int HEAD_CHAPTERS = 10;

    /** 最新档抽取章数：末 10 章。 */
    public static final int RECENT_CHAPTERS = 10;

    /** 随机档每段章数。 */
    public static final int RANDOM_SEGMENT_CHAPTERS = 5;

    /** 随机档段数：两段互不重叠。 */
    public static final int RANDOM_SEGMENTS = 2;

    /** 随机档硬性排除的书头章数：前 10 章不参与随机（与默认档的取样区间错开）。 */
    public static final int RANDOM_EXCLUDED_HEAD = HEAD_CHAPTERS;

    /** 历史常量名，语义仍是「默认档章数」。 */
    public static final int DEFAULT_COUNT = HEAD_CHAPTERS;

    /** 随机档样本总章数。 */
    public static final int RANDOM_TOTAL_CHAPTERS = RANDOM_SEGMENT_CHAPTERS * RANDOM_SEGMENTS;

    /** 章数上限：只有历史档位（mid/mid_recent）还会读到它，防止旧链接传上百章。 */
    public static final int MAX_COUNT = 20;

    /** 默认实例：前 10 章。 */
    public static SampleSpec defaultSpec() {
        return new SampleSpec(HEAD, HEAD_CHAPTERS);
    }

    /**
     * 归一化构造：三档固定章数，忽略调用方传的 count。
     *
     * <p>固定章数是刻意的：界面上的「默认 / 最新 / 随机」就是三种配方，
     * 让残留的 sampleCount 影响它们，会出现「选了最新 10 章却按 17 章跑」的错位。</p>
     *
     * @param strategy 策略标识（null / 空 / 非法值 → 默认前 10 章）
     * @param count    章数，仅历史档位（mid / mid_recent）生效
     * @return 归一化后的取样描述
     */
    public static SampleSpec of(String strategy, int count) {
        String raw = strategy == null ? "" : strategy.trim();
        if (!isExplicit(raw)) {
            return defaultSpec();
        }
        return switch (raw) {
            case HEAD -> new SampleSpec(HEAD, HEAD_CHAPTERS);
            case RECENT -> new SampleSpec(RECENT, RECENT_CHAPTERS);
            // 随机档的 count 是"每段章数"，段数固定 2
            case RANDOM -> new SampleSpec(RANDOM, RANDOM_SEGMENT_CHAPTERS);
            case MID, MID_RECENT -> new SampleSpec(raw, clampCount(count));
            default -> defaultSpec();
        };
    }

    /** 是否显式指定了合法策略（未传 / 非法值都不算）。 */
    private static boolean isExplicit(String strategy) {
        return HEAD.equals(strategy) || MID.equals(strategy) || RECENT.equals(strategy)
                || RANDOM.equals(strategy) || MID_RECENT.equals(strategy);
    }

    private static int clampCount(int count) {
        int safeCount = count <= 0 ? DEFAULT_COUNT : count;
        return Math.min(Math.max(safeCount, 1), MAX_COUNT);
    }

    /** 是否就是默认取样（前 10 章）：拣章时可以直接走"从第 1 章起连续 N 章"的捷径。 */
    public boolean isDefault() {
        return HEAD.equals(strategy) && count == HEAD_CHAPTERS;
    }

    /** 样本总章数：随机档是「每段 × 段数」，其余就是 count。 */
    public int totalChapters() {
        return RANDOM.equals(strategy) ? count * RANDOM_SEGMENTS : count;
    }

    /** 是否走第三方会员通道（最新档与随机档）。 */
    public boolean usesThirdParty() {
        return RECENT.equals(strategy) || RANDOM.equals(strategy);
    }

    /**
     * 落库用的档位名：{@code head} / {@code recent} / {@code random2x5} / {@code mid} / {@code mid_recent}。
     * 与数据源前缀拼成 {@code sample_mode}，如 {@code third:random2x5}。
     */
    public String modeName() {
        return RANDOM.equals(strategy) ? "random2x5" : strategy;
    }
}
