package org.example.fqxs.service;

import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.client.ThirdPartyCatalogClient;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 目录双源的边界：官方优先、失败才回退，且回退不能把「上游被打穿」藏起来。
 */
@ExtendWith(MockitoExtension.class)
class ChapterDirectoryServiceTest {

    @Mock
    private ChapterClient chapterClient;

    @Mock
    private ThirdPartyCatalogClient thirdPartyCatalogClient;

    @Mock
    private UpstreamThrottle upstreamThrottle;

    @InjectMocks
    private ChapterDirectoryService service;

    private static List<ChapterRef> official() {
        return List.of(new ChapterRef("a", "第1章", "第一卷", false));
    }

    private static List<ChapterRef> thirdParty() {
        return List.of(new ChapterRef("a", "第1章", "第一卷", false),
                new ChapterRef("b", "第2章", "第一卷", true));
    }

    /** 正常情况绝不碰第二条路：回退只在官方已经抛错时发生。 */
    @Test
    void officialDirectoryIsPreferredAndThirdPartyNeverAsked() {
        when(chapterClient.listChapters("42")).thenReturn(official());

        assertEquals(1, service.listChapters("42", 300).size());

        verify(upstreamThrottle).awaitSlot(300);
        verifyNoInteractions(thirdPartyCatalogClient);
    }

    /** 官方被风控（200 + 空 body）时改走第三方目录，并把两边的来源都记进日志之外的行为里。 */
    @Test
    void officialFailureFallsBackToThirdPartyCatalog() {
        when(chapterClient.listChapters("42")).thenThrow(new CrawlException("章节目录为空（bookId=42），可能被上游风控"));
        when(thirdPartyCatalogClient.isUsable()).thenReturn(true);
        when(thirdPartyCatalogClient.listChapters("42")).thenReturn(thirdParty());

        List<ChapterRef> chapters = service.listChapters("42", 300);

        assertEquals(2, chapters.size(), "回退结果要完整交给调用方");
        assertTrue(chapters.get(1).locked());
        verify(upstreamThrottle, times(2)).awaitSlot(300);
    }

    /** 回退结果同样进缓存：第二本不该再打一次 198KB 的第三方目录。 */
    @Test
    void fallbackResultIsCachedForTheNextCall() {
        when(chapterClient.listChapters("42")).thenThrow(new CrawlException("上游返回 503"));
        when(thirdPartyCatalogClient.isUsable()).thenReturn(true);
        when(thirdPartyCatalogClient.listChapters("42")).thenReturn(thirdParty());

        service.listChapters("42", 300);
        List<ChapterRef> second = service.listChapters("42", 300);

        assertEquals(2, second.size());
        verify(thirdPartyCatalogClient, times(1)).listChapters("42");
        verify(chapterClient, times(1)).listChapters("42");
        verify(upstreamThrottle, times(2)).awaitSlot(300);
        assertTrue(service.isCached("42"));
    }

    /** 两边都失败要把两条原因都带出来，否则排障时只看到一半。 */
    @Test
    void bothSourcesFailingMentionsBothReasons() {
        when(chapterClient.listChapters("42")).thenThrow(new CrawlException("官方空响应"));
        when(thirdPartyCatalogClient.isUsable()).thenReturn(true);
        when(thirdPartyCatalogClient.listChapters("42")).thenThrow(new CrawlException("第三方线路全在冷却"));

        CrawlException failure = assertThrows(CrawlException.class, () -> service.listChapters("42", 300));

        assertTrue(failure.getMessage().contains("官方空响应"));
        assertTrue(failure.getMessage().contains("第三方线路全在冷却"));
    }

    /** 没有可用的第三方线路时，抛出的是官方那次失败，不编造原因。 */
    @Test
    void withoutUsableFallbackTheOriginalFailureIsRethrown() {
        CrawlException official = new CrawlException("官方目录被风控");
        when(chapterClient.listChapters("42")).thenThrow(official);
        when(thirdPartyCatalogClient.isUsable()).thenReturn(false);

        CrawlException failure = assertThrows(CrawlException.class, () -> service.listChapters("42", 300));

        assertEquals(official.getMessage(), failure.getMessage());
        verify(thirdPartyCatalogClient, never()).listChapters(anyString());
    }

    /** 官方缓存命中时零等待：槽位与 AI 判定共用，白占会把真正的回源请求推后。 */
    @Test
    void officialCacheHitSkipsTheUpstreamSlot() {
        when(chapterClient.hasCachedDirectory("42")).thenReturn(true);
        when(chapterClient.listChapters("42")).thenReturn(official());

        assertEquals(1, service.listChapters("42", 300).size());

        verifyNoInteractions(upstreamThrottle);
        verifyNoInteractions(thirdPartyCatalogClient);
    }

    /** 只给 ID 的方法与目录方法同源，取样侧靠它数章号。 */
    @Test
    void listChapterIdsMapsTheSameFallbackChain() {
        when(chapterClient.listChapters("42")).thenThrow(new CrawlException("上游返回 503"));
        when(thirdPartyCatalogClient.isUsable()).thenReturn(true);
        when(thirdPartyCatalogClient.listChapters("42")).thenReturn(thirdParty());

        assertEquals(List.of("a", "b"), service.listChapterIds("42", 800));
    }

    /** 间隔传 0 表示不限速：单测与探针用它跳过等待。 */
    @Test
    void zeroIntervalMeansNoThrottling() {
        when(chapterClient.listChapters("42")).thenReturn(official());

        service.listChapters("42", 0);

        verify(upstreamThrottle).awaitSlot(0);
        assertFalse(service.isCached("no-such-book"));
    }
}
