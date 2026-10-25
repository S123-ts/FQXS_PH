package org.example.fqxs.service;

import org.example.fqxs.client.ThirdPartyChapterClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.model.SampleSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 三档取样规则契约：
 *
 * <ul>
 *   <li>默认档：全书前 10 章，走官方（{@link ChapterContentService}，库优先）；</li>
 *   <li>最新档：<b>全书末尾</b> 10 章，走第三方——不再按可读区钳制；</li>
 *   <li>随机档：第 11 章起两段各 5 章，走第三方，硬性排除书头；</li>
 *   <li>第三方不可用 / 章节数不足：直接报错，静默降级会让 sample_mode 说谎。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AiSampleCollectorTest {

    @Mock
    private ChapterDirectoryService directoryService;

    /** 默认 mock 的 isUsable() 返回 false，需要第三方档的用例要显式打开。 */
    @Mock
    private ThirdPartyChapterClient thirdPartyChapterClient;

    @Mock
    private ChapterContentService chapterContentService;

    private AiDetectConfig config;

    private AiSampleCollector collector;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        config.setRequestIntervalMillis(0);
        config.setMinChapterChars(500);
        config.setMinSampleChars(1500);
        config.setMaxCharsPerBook(8000);
        config.setSampleRequestLimit(15);
        config.setFreeHeadChapters(10);
        collector = new AiSampleCollector(directoryService, thirdPartyChapterClient,
                chapterContentService, config, new UpstreamThrottle());
    }

    @Test
    void defaultRuleTakesFirstTenChaptersThroughChapterStore() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(200));
        lenient().when(chapterContentService.getOrFetch(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> full(invocation.getArgument(1), 600, 600));

        AiSampleCollector.CollectedSample collected = collector.collect("b1", null);

        assertThat(collected.mode()).isEqualTo("official:head");
        assertThat(collected.span()).isEqualTo("1-10");
        assertThat(collected.text()).contains("第1章").contains("第10章");
        // 官方档的回源由 ChapterContentService 负责（库优先），目录走 ChapterDirectoryService，不碰第三方
        verifyNoInteractions(thirdPartyChapterClient);
        verify(chapterContentService, times(10)).getOrFetch(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void defaultRuleIsUsedWhenStrategyIsBlank() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(30));
        lenient().when(chapterContentService.getOrFetch(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> full(invocation.getArgument(1), 600, 600));

        AiSampleCollector.CollectedSample collected = collector.collect("b1", SampleSpec.of("", 0));

        assertThat(collected.mode()).isEqualTo("official:head");
        assertThat(collected.span()).isEqualTo("1-10");
    }

    @Test
    void recentRuleTakesLastTenChaptersFromThirdParty() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(200));
        when(thirdPartyChapterClient.isUsable()).thenReturn(true);
        lenient().when(thirdPartyChapterClient.fetchChapter(anyString()))
                .thenAnswer(invocation -> full(invocation.getArgument(0), 600, 600));

        AiSampleCollector.CollectedSample collected = collector.collect("b1", SampleSpec.of("recent", 0));

        // 全书末尾，而不是可读区末尾：这是本次重构最直观的证据
        assertThat(collected.span()).isEqualTo("191-200");
        assertThat(collected.mode()).isEqualTo("third:recent");
        verify(thirdPartyChapterClient, times(10)).fetchChapter(anyString());
        verifyNoInteractions(chapterContentService);
    }

    @Test
    void randomRulePicksTwoNonOverlappingSegmentsAfterTheTenthChapter() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(200));
        when(thirdPartyChapterClient.isUsable()).thenReturn(true);
        lenient().when(thirdPartyChapterClient.fetchChapter(anyString()))
                .thenAnswer(invocation -> full(invocation.getArgument(0), 600, 600));

        AiSampleCollector.CollectedSample collected = collector.collect("b1", SampleSpec.of("random", 0));

        assertThat(collected.mode()).isEqualTo("third:random2x5");
        List<int[]> segments = parseSpan(collected.span());
        // 两段起点之差允许正好等于段长（那时它们是首尾相接的 10 章，spanOf 会压成一段），
        // 所以断言"一共 10 章、都在第 11 章之后、段间不重叠"，而不是死盯"必须两段"
        int chapters = 0;
        int previousEnd = 0;
        for (int[] segment : segments) {
            assertThat(segment[0]).isGreaterThan(previousEnd);
            assertThat(segment[1] - segment[0] + 1).isBetween(5, 10);
            // 1-based 章号必须 >= 11：前 10 章不参与随机
            assertThat(segment[0]).isGreaterThanOrEqualTo(11);
            assertThat(segment[1]).isLessThanOrEqualTo(200);
            chapters += segment[1] - segment[0] + 1;
            previousEnd = segment[1];
        }
        assertThat(chapters).isEqualTo(10);
    }

    @Test
    void randomRuleRejectsBookTooShortForTwoSegments() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(19));
        when(thirdPartyChapterClient.isUsable()).thenReturn(true);

        assertThatThrownBy(() -> collector.collect("b1", SampleSpec.of("random", 0)))
                .isInstanceOf(CrawlException.class)
                .hasMessageContaining("章节数不足");
    }

    @Test
    void thirdPartyUnavailableFailsInsteadOfFallingBackToHead() {
        when(thirdPartyChapterClient.isUsable()).thenReturn(false);

        assertThatThrownBy(() -> collector.collect("b1", SampleSpec.of("recent", 0)))
                .isInstanceOf(CrawlException.class)
                .hasMessageContaining("会员正文通道暂不可用");
        // 关键：不许偷偷改走官方前 10 章，否则 sample_mode 与实际取样不符
        verifyNoInteractions(chapterContentService);
        verify(thirdPartyChapterClient, never()).fetchChapter(anyString());
    }

    @Test
    void thirdPartyFailureOnOneChapterIsSkipped() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(200));
        when(thirdPartyChapterClient.isUsable()).thenReturn(true);
        lenient().when(thirdPartyChapterClient.fetchChapter(anyString()))
                .thenAnswer(invocation -> full(invocation.getArgument(0), 600, 600));
        // 具体桩必须放在通用桩之后：Mockito 后者覆盖前者
        when(thirdPartyChapterClient.fetchChapter("c200"))
                .thenThrow(new CrawlException("第三方正文为空，上游共享云手机可能已断开"));

        AiSampleCollector.CollectedSample collected = collector.collect("b1", SampleSpec.of("recent", 0));

        // 单章失败只跳过本章，其余 9 章照常送判
        assertThat(collected.span()).isEqualTo("191-199");
        assertThat(collected.mode()).isEqualTo("third:recent");
    }

    @Test
    void requestBudgetCapsChapterFetches() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(200));
        when(thirdPartyChapterClient.isUsable()).thenReturn(true);
        lenient().when(thirdPartyChapterClient.fetchChapter(anyString()))
                .thenAnswer(invocation -> new ChapterSample(invocation.getArgument(0), "", "试".repeat(150), 150));
        config.setSampleRequestLimit(3);

        assertThatThrownBy(() -> collector.collect("b1", SampleSpec.of("recent", 0)))
                .isInstanceOf(CrawlException.class)
                .hasMessageContaining("可用正文不足");

        verify(thirdPartyChapterClient, times(3)).fetchChapter(anyString());
    }

    @Test
    void failsWhenAllOfficialChaptersArePreviewOnly() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(20));
        lenient().when(chapterContentService.getOrFetch(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> new ChapterSample(invocation.getArgument(1), "", "试".repeat(150), 150));

        assertThatThrownBy(() -> collector.collect("b1", null))
                .isInstanceOf(CrawlException.class)
                .hasMessageContaining("可用正文不足");
    }

    @Test
    void planMarksThirdPartyForRecentAndOfficialForHead() {
        when(directoryService.listChapterIds(eq("b1"), anyLong())).thenReturn(ids(200));
        lenient().when(thirdPartyChapterClient.isUsable()).thenReturn(true);

        AiSampleCollector.ChapterPlan head = collector.plan("b1", SampleSpec.of("head", 0));
        AiSampleCollector.ChapterPlan recent = collector.plan("b1", SampleSpec.of("recent", 0));

        assertThat(head.thirdParty()).isFalse();
        assertThat(head.indices()).hasSize(10).startsWith(0);
        assertThat(recent.thirdParty()).isTrue();
        assertThat(recent.indices()).hasSize(10).endsWith(199);
    }

    /** 「11-15,120-124」→ {{11,15},{120,124}} */
    private static List<int[]> parseSpan(String span) {
        List<int[]> segments = new ArrayList<>();
        for (String part : span.split(",")) {
            String[] bounds = part.split("-");
            int from = Integer.parseInt(bounds[0]);
            int to = bounds.length > 1 ? Integer.parseInt(bounds[1]) : from;
            segments.add(new int[]{from, to});
        }
        return segments;
    }

    private static List<String> ids(int total) {
        return IntStream.rangeClosed(1, total).mapToObj(i -> "c" + i).toList();
    }

    private static ChapterSample full(String itemId, int chapterNo, int length) {
        return new ChapterSample(itemId, "第" + chapterNo + "章", "正".repeat(length), length);
    }
}
