package org.example.fqxs.service;

import org.example.fqxs.client.ThirdPartyBookClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.repository.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补全器的硬约束：一次最多 batchMax 本、只补候选、性别只取一次、连续失败 3 本停。
 */
@ExtendWith(MockitoExtension.class)
class BookMetaEnricherTest {

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private ThirdPartyBookClient bookClient;

    private AiDetectConfig config;

    private BookMetaEnricher enricher;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        config.setThirdPartyEnabled(true);
        config.setBookMetaBatchMax(10);
        config.setBookMetaIntervalMillis(0);
        config.setBookMetaTopRank(50);
        config.setRatingRefreshDays(10);
        enricher = new BookMetaEnricher(novelRepository, bookClient, config);
    }

    private static Novel row(String bookId) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        return novel;
    }

    @Test
    void enrichesOnlyRequestedBatchAndCountsRemaining() {
        List<Novel> candidates = new ArrayList<>(List.of(row("1"), row("2"), row("3"), row("4")));
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(candidates);
        when(bookClient.fetchMeta(anyString()))
                .thenReturn(new ThirdPartyBookClient.BookMeta(1, 8.6));
        when(novelRepository.findAllByBookId(anyString()))
                .thenAnswer(invocation -> List.of(
                        candidates.stream().filter(n -> n.getBookId().equals(invocation.getArgument(0)))
                                .findFirst().orElseThrow()));

        BookMetaEnricher.Result result = enricher.enrich(2, "read");

        assertEquals(2, result.updated());
        assertEquals(0, result.failed());
        assertEquals(2, result.remaining());
        verify(bookClient, times(2)).fetchMeta(anyString());
        assertEquals(1, candidates.get(0).getAuthorGender());
        assertEquals(8.6, candidates.get(0).getRating(), 0.001);
        assertNull(candidates.get(2).getAuthorGender());
    }

    @Test
    void skipsBooksWhoseMetaIsFresh() {
        Novel fresh = row("1");
        fresh.setAuthorGenderCheckedTime(LocalDateTime.now());
        fresh.setRatingCheckedTime(LocalDateTime.now());
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(List.of(fresh));

        BookMetaEnricher.Result result = enricher.enrich(5, "read");

        assertEquals(0, result.updated());
        assertEquals(0, result.remaining());
        verify(bookClient, never()).fetchMeta(anyString());
    }

    @Test
    void keepsFirstGenderWhenOnlyRatingNeedsRefresh() {
        Novel stale = row("1");
        stale.setAuthorGender(0);
        stale.setAuthorGenderCheckedTime(LocalDateTime.now().minusDays(30));
        stale.setRatingCheckedTime(LocalDateTime.now().minusDays(30));
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(List.of(stale));
        when(bookClient.fetchMeta("1")).thenReturn(new ThirdPartyBookClient.BookMeta(1, 9.1));
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(stale));

        enricher.enrich(5, "read");

        // 性别只取一次：即使这次返回 1，也不覆盖首次的 0
        assertEquals(0, stale.getAuthorGender());
        assertEquals(9.1, stale.getRating(), 0.001);
    }

    @Test
    void batchIsClampedToConfiguredMax() {
        config.setBookMetaBatchMax(2);
        List<Novel> candidates = new ArrayList<>(List.of(row("1"), row("2"), row("3")));
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(candidates);
        when(bookClient.fetchMeta(anyString())).thenReturn(new ThirdPartyBookClient.BookMeta(1, 8.0));
        when(novelRepository.findAllByBookId(anyString())).thenAnswer(invocation -> List.of(
                candidates.stream().filter(n -> n.getBookId().equals(invocation.getArgument(0)))
                        .findFirst().orElseThrow()));

        BookMetaEnricher.Result result = enricher.enrich(50, "read");

        assertEquals(2, result.requested());
        verify(bookClient, times(2)).fetchMeta(anyString());
    }

    @Test
    void stopsAfterThreeConsecutiveFailures() {
        List<Novel> candidates = new ArrayList<>(List.of(
                row("1"), row("2"), row("3"), row("4"), row("5")));
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(candidates);
        when(bookClient.fetchMeta(anyString())).thenThrow(new RuntimeException("上游 503"));

        BookMetaEnricher.Result result = enricher.enrich(5, "read");

        assertEquals(3, result.failed());
        assertEquals(5, result.remaining());
        verify(bookClient, times(3)).fetchMeta(anyString());
    }
}
