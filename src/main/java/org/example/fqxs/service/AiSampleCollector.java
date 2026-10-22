package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.client.ThirdPartyChapterClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.model.SampleSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 取样器：按三档规则抽章节、过滤 VIP 试读、拼接样本，并维护全局上游节流。
 *
 * <p>2026-10-22 起重构为「规则 → 位置 + 数据源 + 是否落库」的固定配方：</p>
 * <ul>
 *   <li>{@code head}（默认）：全书前 10 章，走官网 SSR，正文落库（{@link ChapterContentService}），
 *       第二次起零上游请求；</li>
 *   <li>{@code recent}：全书最后 10 章，走第三方会员通道；</li>
 *   <li>{@code random}：第 11 章起随机抽两段、每段 5 章，走第三方会员通道。</li>
 * </ul>
 *
 * <p><b>位置按全书算，不按"可读区"算</b>——这是本次最关键的一处修正。旧实现里
 * {@code readable = min(total, free-head-chapters)}，所有策略都在可读区内挑，
 * 于是「最新 10 章」实际取到的是第 1–10 章、「随机」也只在第 1–10 章里随机。</p>
 *
 * <p>第三方通道不可用（冷却/关闭）时，{@code recent} / {@code random} <b>直接报错</b>，
 * 不静默降级成前 10 章：降级会让 {@code sample_mode} 与实际取样不符，结论无法解释。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSampleCollector {

    /** 数据源：官方阅读页 SSR（可落库）。 */
    private static final boolean SOURCE_OFFICIAL = false;

    /** 数据源：第三方会员通道（不落库）。 */
    private static final boolean SOURCE_THIRD_PARTY = true;

    /**
     * 一次取样的结果：正文 + 来源档位 + 实际章号跨度。
     *
     * @param text 拼接后的正文（已按 max-chars-per-book 截断）
     * @param mode 档位：{@code official:head} / {@code third:recent} / {@code third:random2x5}
     * @param span 采用章号（1-based）的区间压缩，如 1-4,7,12
     */
    public record CollectedSample(String text, String mode, String span) {
    }

    /**
     * 章节计划：只算"该查哪几章"，不抓正文。
     *
     * <p>逐章校验复用同一套拣章规则，但一次只抓一章，所以需要一份中间结果，
     * 避免把整个取样流程重跑一遍。</p>
     *
     * @param itemIds    全书章节 id（顺序即章号）
     * @param indices    本次取样/校验涉及的章号（0-based，保序）
     * @param readable   可读区上限（0-based 边界：小于它是官方免费全文），仅供历史档位使用
     * @param total      全书章节数
     * @param thirdParty 本批是否走第三方通道（head 为 false，recent/random 为 true）
     */
    public record ChapterPlan(List<String> itemIds, List<Integer> indices, int readable, int total,
                              boolean thirdParty) {

        /** 没有可校验的章节。 */
        public boolean isEmpty() {
            return indices.isEmpty();
        }
    }

    private final ChapterClient chapterClient;
    private final ThirdPartyChapterClient thirdPartyChapterClient;
    private final ChapterContentService chapterContentService;
    private final AiDetectConfig aiDetectConfig;

    /** 全局上游节流槽：所有取样线程共享，模型调用不走这里。 */
    private final AtomicLong nextUpstreamSlotMillis = new AtomicLong();

    /**
     * 计算章节计划：取样规则决定"查哪几章、走哪一路"。
     *
     * @param bookId 书籍 ID
     * @param sample 取样规则，null 走默认（前 10 章）
     * @return 章节计划
     * @throws CrawlException 第三方通道不可用（recent/random），或章节数凑不出两段
     */
    public ChapterPlan plan(String bookId, SampleSpec sample) {
        SampleSpec effective = sample == null ? SampleSpec.defaultSpec() : sample;
        if (effective.usesThirdParty()) {
            requireThirdParty();
        }
        List<String> itemIds = chapterClient.listChapterIds(bookId);
        int total = itemIds.size();
        int readable = readableUpperBound(total);
        Set<Integer> picked = pickIndices(total, readable, effective);
        return new ChapterPlan(itemIds, List.copyOf(picked), readable, total, effective.usesThirdParty());
    }

    /**
     * 按取样规则抽章节拼接送判文本。
     *
     * @param bookId 书籍 ID
     * @param sample 取样规则，null 走默认（前 10 章）
     * @return 取样正文
     * @throws CrawlException 第三方通道不可用、章节数不足、可用正文不足（全部是试读/VIP）
     */
    public CollectedSample collect(String bookId, SampleSpec sample) {
        SampleSpec effective = sample == null ? SampleSpec.defaultSpec() : sample;
        ChapterPlan plan = plan(bookId, effective);
        List<String> itemIds = plan.itemIds();
        int total = plan.total();
        boolean thirdParty = plan.thirdParty();
        Set<Integer> picked = new LinkedHashSet<>(plan.indices());

        int maxChars = aiDetectConfig.getMaxCharsPerBook();
        int minChapterChars = aiDetectConfig.getMinChapterChars();
        StringBuilder sampleText = new StringBuilder();
        List<String> used = new ArrayList<>();
        List<Integer> usedIndices = new ArrayList<>();
        int previewSkipped = 0;
        int fetchSkipped = 0;
        // 单本请求预算：策略章节 + 回退扫描合计，避免一次判定打太多上游请求
        int requestBudget = Math.max(1, aiDetectConfig.getSampleRequestLimit());
        int requests = 0;

        for (int idx : picked) {
            if (sampleText.length() >= maxChars || requests >= requestBudget) {
                break;
            }
            requests++;
            int added = appendChapter(sampleText, bookId, plan, idx, minChapterChars, thirdParty);
            if (added == 0) {
                if (thirdParty) {
                    fetchSkipped++;
                } else {
                    previewSkipped++;
                }
            } else {
                used.add("第" + (idx + 1) + "章(" + added + "字)");
                usedIndices.add(idx);
            }
        }

        /* 回退扫描只属于官方档：官方可读区里还有完整免费章时补足样本。
           第三方档不回退——退回官方前 10 章等于换了取样区间，结论无法解释 */
        if (!thirdParty && sampleText.length() < aiDetectConfig.getMinSampleChars()) {
            int readable = plan.readable();
            for (int idx = 0; idx < readable && sampleText.length() < maxChars && requests < requestBudget; idx++) {
                if (picked.contains(idx)) {
                    continue;
                }
                requests++;
                int added = appendChapter(sampleText, bookId, plan, idx, minChapterChars, SOURCE_OFFICIAL);
                if (added == 0) {
                    previewSkipped++;
                } else {
                    used.add("第" + (idx + 1) + "章(" + added + "字)");
                    usedIndices.add(idx);
                }
            }
        }

        if (sampleText.length() < aiDetectConfig.getMinSampleChars()) {
            throw new CrawlException("可用正文不足（" + sampleText.length() + " 字，疑似全部为试读/VIP 章节），"
                    + "无法可靠判定: bookId=" + bookId + ", 规则=" + effective.modeName());
        }
        String source = thirdParty ? "third" : "official";
        log.info("取样完成: bookId={}, 规则={}, 来源={}, 采用 {} 章/{} 字, 试读跳过 {} 章, 抓取失败跳过 {} 章, "
                        + "请求 {}/{} 章, 全书 {} 章, 章节={}",
                bookId, effective.modeName(), source, used.size(), sampleText.length(),
                previewSkipped, fetchSkipped, requests, requestBudget, total, used);
        String text = sampleText.length() > maxChars ? sampleText.substring(0, maxChars) : sampleText.toString();
        return new CollectedSample(text, source + ":" + effective.modeName(), spanOf(usedIndices));
    }

    /**
     * 采用的章号压成区间串（1-based）：1-4,7,12。超长截断，列宽只有 40。
     */
    private String spanOf(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(indices));
        StringBuilder span = new StringBuilder();
        int start = sorted.get(0);
        int end = start;
        for (int i = 1; i <= sorted.size(); i++) {
            int current = i < sorted.size() ? sorted.get(i) : -1;
            if (current == end + 1) {
                end = current;
                continue;
            }
            if (span.length() > 0) {
                span.append(',');
            }
            int from = start + 1;
            int to = end + 1;
            span.append(from == to ? String.valueOf(from) : from + "-" + to);
            if (span.length() >= 40) {
                break;
            }
            start = current;
            end = current;
        }
        String result = span.toString();
        return result.length() > 40 ? result.substring(0, 39) + "…" : result;
    }

    /**
     * 可读区上限：全书章数按 {@code free-head-chapters} 钳制。
     *
     * <p>只有历史档位（mid / mid_recent）还在用这个边界；三档新规则的位置按全书算。</p>
     *
     * @param total 全书章节数
     * @return 可参与取样的章节数
     */
    private int readableUpperBound(int total) {
        int cap = aiDetectConfig.getFreeHeadChapters();
        return cap <= 0 ? total : Math.min(total, cap);
    }

    /**
     * 按规则生成候选章号（0-based，保序去重）。
     *
     * @param total    全书章节数
     * @param readable 可读区章节数（历史档位用）
     * @param sample   取样规则
     * @return 候选章号
     */
    private Set<Integer> pickIndices(int total, int readable, SampleSpec sample) {
        return switch (sample.strategy()) {
            case SampleSpec.HEAD -> headIndices(total, sample.count());
            case SampleSpec.RECENT -> recentIndices(total, sample.count());
            case SampleSpec.RANDOM -> randomTwoSegments(total, sample.count());
            // 历史档位：仍按可读区算，界面已不提供
            case SampleSpec.MID -> midIndices(readable, sample.count());
            case SampleSpec.MID_RECENT -> midRecentIndices(readable);
            default -> throw new IllegalArgumentException("不支持的取样规则: " + sample.strategy());
        };
    }

    /** 前 N 章：全书从第 1 章起连续取。 */
    private Set<Integer> headIndices(int total, int requested) {
        Set<Integer> picked = new LinkedHashSet<>();
        int count = Math.min(Math.max(requested, 1), total);
        for (int i = 0; i < count; i++) {
            picked.add(i);
        }
        return picked;
    }

    /** 最新 N 章：全书末尾。 */
    private Set<Integer> recentIndices(int total, int requested) {
        Set<Integer> picked = new LinkedHashSet<>();
        int count = Math.min(requested, total);
        for (int i = Math.max(0, total - count); i < total; i++) {
            picked.add(i);
        }
        return picked;
    }

    /**
     * 随机两段：从第 {@link SampleSpec#RANDOM_EXCLUDED_HEAD} 章起，抽两段互不重叠、每段 N 章。
     *
     * <p>两段起点之差必须 ≥ 段长，否则会重叠成一整段；重试 20 次仍取不到就退化成
     * 「池尾 + 池头」两段。池子连两段都放不下时直接报错，不静默变成一段——
     * 样本量从 10 章掉到 5 章会显著抬高误判率。</p>
     */
    private Set<Integer> randomTwoSegments(int total, int segmentSize) {
        int poolStart = SampleSpec.RANDOM_EXCLUDED_HEAD;
        int lastStart = total - segmentSize;
        if (lastStart < poolStart || total - poolStart < segmentSize * SampleSpec.RANDOM_SEGMENTS) {
            throw new CrawlException("章节数不足，随机两段各 " + segmentSize + " 章需至少 "
                    + (poolStart + segmentSize * SampleSpec.RANDOM_SEGMENTS) + " 章，本书仅 " + total + " 章");
        }
        int span = lastStart - poolStart + 1;
        int firstStart = -1;
        int secondStart = -1;
        for (int attempt = 0; attempt < 20; attempt++) {
            int a = poolStart + ThreadLocalRandom.current().nextInt(span);
            int b = poolStart + ThreadLocalRandom.current().nextInt(span);
            if (Math.abs(a - b) >= segmentSize) {
                firstStart = Math.min(a, b);
                secondStart = Math.max(a, b);
                break;
            }
        }
        if (firstStart < 0) {
            // 兜底：池子头尾各一段，一定是互不重叠的最远两段
            firstStart = poolStart;
            secondStart = lastStart;
        }
        Set<Integer> picked = new LinkedHashSet<>();
        for (int i = firstStart; i < firstStart + segmentSize; i++) {
            picked.add(i);
        }
        for (int i = secondStart; i < secondStart + segmentSize; i++) {
            picked.add(i);
        }
        return new LinkedHashSet<>(new TreeSet<>(picked));
    }

    /** 历史档位：可读区中段 N 章。 */
    private Set<Integer> midIndices(int readable, int requested) {
        Set<Integer> picked = new LinkedHashSet<>();
        int count = Math.min(requested, readable);
        int start = Math.max(0, readable / 2 - count / 2);
        for (int i = start; i < start + count && i < readable; i++) {
            picked.add(i);
        }
        return picked;
    }

    /** 历史档位：可读区中段若干章 + 末尾若干章。 */
    private Set<Integer> midRecentIndices(int readable) {
        Set<Integer> picked = new LinkedHashSet<>();
        int midCount = Math.min(aiDetectConfig.getSampleMidChapters(), readable);
        int recentCount = Math.min(aiDetectConfig.getSampleRecentChapters(), readable);
        int midStart = Math.max(0, readable / 2 - midCount / 2);
        for (int i = midStart; i < midStart + midCount && i < readable; i++) {
            picked.add(i);
        }
        for (int i = Math.max(0, readable - recentCount); i < readable; i++) {
            picked.add(i);
        }
        return picked;
    }

    /** 第三方档的前置校验：不可用就明确失败，不降级成官方前 10 章。 */
    private void requireThirdParty() {
        if (!thirdPartyChapterClient.isUsable()) {
            throw new CrawlException("会员正文通道暂不可用（冷却中或已关闭），"
                    + "取不到最新/随机章节；请稍后重试，或改用默认「前 10 章」规则");
        }
    }

    /**
     * 抓一章并追加到样本。
     *
     * @param thirdParty 是否走第三方通道；官方档走 {@link ChapterContentService}（库优先）
     * @return 追加的字符数；0 表示试读片段或第三方取失败，已跳过
     * @throws CrawlException 官方档抓取失败（有库兜底，失败即上游异常，交给整本报错）
     */
    private int appendChapter(StringBuilder sampleText, String bookId, ChapterPlan plan, int index,
                              int minChapterChars, boolean thirdParty) {
        String itemId = plan.itemIds().get(index);
        ChapterSample chapter;
        if (thirdParty) {
            try {
                awaitUpstreamSlot();
                chapter = thirdPartyChapterClient.fetchChapter(itemId);
            } catch (CrawlException failure) {
                // 别人的共享线路随时会断：单章失败跳过，凑不够样本时由总量校验报错
                log.warn("第三方章节取失败，本章跳过: itemId={}, 原因={}", itemId, failure.getMessage());
                return 0;
            }
        } else {
            // 库命中时零等待零请求；未命中才占一个上游槽并回源落库
            chapter = chapterContentService.getOrFetch(bookId, itemId, index, this::awaitUpstreamSlot);
        }
        String text = chapter.text() == null ? "" : chapter.text();
        if (text.length() < minChapterChars) {
            return 0;
        }
        int before = sampleText.length();
        sampleText.append("【第").append(index + 1).append("章】");
        if (chapter.title() != null && !chapter.title().isBlank()) {
            sampleText.append(chapter.title());
        }
        sampleText.append('\n').append(text).append('\n');
        return sampleText.length() - before;
    }

    /**
     * 全局上游节流：章节抓取前先占一个时间槽，把下一次请求推后 requestIntervalMillis。
     * 并发初筛时所有线程共享这个节拍；模型调用不走这里，库命中也不该白等——
     * 所以只有真正回源的分支才调它。
     */
    public void awaitUpstreamSlot() {
        long interval = aiDetectConfig.getRequestIntervalMillis();
        if (interval <= 0) {
            return;
        }
        long slot = nextUpstreamSlotMillis.updateAndGet(prev ->
                Math.max(prev, System.currentTimeMillis()) + interval);
        long waitMillis = slot - System.currentTimeMillis();
        if (waitMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
