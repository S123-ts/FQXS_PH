package org.example.fqxs.service;

import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.NovelChapter;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.repository.NovelChapterRepository;
import org.example.fqxs.util.ChapterTextCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取章服务契约：库优先、未命中才回源并落库、写入失败不影响判定。
 */
@ExtendWith(MockitoExtension.class)
class ChapterContentServiceTest {

    @Mock
    private NovelChapterRepository chapterRepository;

    @Mock
    private ChapterClient chapterClient;

    @Mock
    private ChapterTextCleaner chapterTextCleaner;

    private AiDetectConfig config;

    private ChapterContentService service;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        service = new ChapterContentService(chapterRepository, chapterClient, chapterTextCleaner, config);
    }

    @Test
    void cacheHitReturnsWithoutUpstreamRequestOrThrottle() {
        NovelChapter row = row("c1", "第1章", "库里的正文");
        when(chapterRepository.findByItemId("c1")).thenReturn(Optional.of(row));
        AtomicInteger throttleCalls = new AtomicInteger();

        ChapterSample sample = service.getOrFetch("b1", "c1", 0, throttleCalls::incrementAndGet);

        assertThat(sample.text()).isEqualTo("库里的正文");
        assertThat(throttleCalls.get()).isZero();
        verify(chapterClient, never()).fetchChapter(anyString());
    }

    @Test
    void cacheMissFetchesCleansAndStores() {
        when(chapterRepository.findByItemId("c1")).thenReturn(Optional.empty());
        when(chapterClient.fetchChapter("c1"))
                .thenReturn(new ChapterSample("c1", "第1章", "原始\u3000正文", 7));
        when(chapterTextCleaner.clean("原始\u3000正文", "第1章"))
                .thenReturn(new ChapterTextCleaner.Cleaned("原始正文", 4, 0));
        AtomicInteger throttleCalls = new AtomicInteger();

        ChapterSample sample = service.getOrFetch("b1", "c1", 2, throttleCalls::incrementAndGet);

        assertThat(sample.text()).isEqualTo("原始正文");
        assertThat(sample.chars()).isEqualTo(4);
        // 节流只在确实回源时占槽
        assertThat(throttleCalls.get()).isEqualTo(1);

        ArgumentCaptor<NovelChapter> saved = ArgumentCaptor.forClass(NovelChapter.class);
        verify(chapterRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBookId()).isEqualTo("b1");
        assertThat(saved.getValue().getItemId()).isEqualTo("c1");
        assertThat(saved.getValue().getChapterIndex()).isEqualTo(2);
        assertThat(saved.getValue().getContent()).isEqualTo("原始正文");
        assertThat(saved.getValue().getCharCount()).isEqualTo(4);
        assertThat(saved.getValue().getSource()).isEqualTo(NovelChapter.SOURCE_OFFICIAL);
    }

    @Test
    void storeFailureDoesNotBreakJudgement() {
        when(chapterRepository.findByItemId("c1")).thenReturn(Optional.empty());
        when(chapterClient.fetchChapter("c1")).thenReturn(new ChapterSample("c1", "第1章", "正文", 2));
        when(chapterTextCleaner.clean(anyString(), anyString()))
                .thenReturn(new ChapterTextCleaner.Cleaned("正文", 2, 0));
        when(chapterRepository.saveAndFlush(any(NovelChapter.class)))
                .thenThrow(new IllegalStateException("数据库已锁"));

        ChapterSample sample = service.getOrFetch("b1", "c1", 0);

        // 缓存是增强，写不进去也要把正文交出去
        assertThat(sample.text()).isEqualTo("正文");
    }

    @Test
    void overlongContentIsTruncatedToColumnWidth() {
        String huge = "正".repeat(NovelChapter.CONTENT_MAX_LENGTH + 500);
        when(chapterRepository.findByItemId("c1")).thenReturn(Optional.empty());
        when(chapterClient.fetchChapter("c1")).thenReturn(new ChapterSample("c1", "第1章", huge, huge.length()));
        when(chapterTextCleaner.clean(anyString(), anyString()))
                .thenReturn(new ChapterTextCleaner.Cleaned(huge, huge.length(), 0));

        service.getOrFetch("b1", "c1", 0);

        ArgumentCaptor<NovelChapter> saved = ArgumentCaptor.forClass(NovelChapter.class);
        verify(chapterRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getContent()).hasSize(NovelChapter.CONTENT_MAX_LENGTH);
        assertThat(saved.getValue().getCharCount()).isEqualTo(NovelChapter.CONTENT_MAX_LENGTH);
    }

    @Test
    void storeCanBeDisabledByConfig() {
        config.setChapterStoreEnabled(false);
        when(chapterClient.fetchChapter("c1")).thenReturn(new ChapterSample("c1", "第1章", "正文", 2));
        when(chapterTextCleaner.clean(anyString(), anyString()))
                .thenReturn(new ChapterTextCleaner.Cleaned("正文", 2, 0));

        ChapterSample sample = service.getOrFetch("b1", "c1", 0);

        assertThat(sample.text()).isEqualTo("正文");
        verify(chapterRepository, never()).findByItemId(anyString());
        verify(chapterRepository, never()).saveAndFlush(any(NovelChapter.class));
    }

    @Test
    void repositoryFailureFallsBackToUpstream() {
        when(chapterRepository.findByItemId("c1")).thenThrow(new IllegalStateException("库不可用"));
        when(chapterClient.fetchChapter("c1")).thenReturn(new ChapterSample("c1", "第1章", "正文", 2));
        when(chapterTextCleaner.clean(anyString(), anyString()))
                .thenReturn(new ChapterTextCleaner.Cleaned("正文", 2, 0));

        assertThat(service.getOrFetch("b1", "c1", 0).text()).isEqualTo("正文");
    }

    @Test
    void emptyContentAfterCleaningIsRejected() {
        when(chapterRepository.findByItemId("c1")).thenReturn(Optional.empty());
        when(chapterClient.fetchChapter("c1")).thenReturn(new ChapterSample("c1", "第1章", "   ", 3));
        when(chapterTextCleaner.clean(anyString(), anyString()))
                .thenReturn(new ChapterTextCleaner.Cleaned("", 0, 0));

        assertThatThrownBy(() -> service.getOrFetch("b1", "c1", 0))
                .isInstanceOf(CrawlException.class)
                .hasMessageContaining("正文为空");
    }

    private static NovelChapter row(String itemId, String title, String content) {
        NovelChapter row = new NovelChapter();
        row.setBookId("b1");
        row.setItemId(itemId);
        row.setChapterIndex(0);
        row.setChapterTitle(title);
        row.setContent(content);
        row.setCharCount(content.length());
        return row;
    }
}
