package org.example.fqxs.service;

import org.example.fqxs.client.ThirdPartyBookClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AuthorMeta;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.exception.ItemUnavailableException;
import org.example.fqxs.exception.LineCoolingException;
import org.example.fqxs.repository.AuthorMetaRepository;
import org.example.fqxs.repository.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    private AuthorMetaRepository authorMetaRepository;

    @Mock
    private ThirdPartyBookClient bookClient;

    @Mock
    private ConfigService configService;

    private AiDetectConfig config;

    private BookMetaEnricher enricher;

    /** 元数据表的替身：save 进、find 出，行为与真表一致，避免每个用例各写一套打桩。 */
    private final Map<String, AuthorMeta> metaStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        config.setThirdPartyEnabled(true);
        config.setBookMetaBatchMax(10);
        config.setBookMetaIntervalMillis(0);
        config.setBookMetaTopRank(50);
        config.setRatingRefreshDays(5);
        enricher = new BookMetaEnricher(novelRepository, authorMetaRepository, bookClient, config,
                configService);
        // 默认全拉：绝大多数用例不关心频道过滤，个别用例再改这一条
        lenient().when(configService.getFetchGender()).thenReturn(ConfigService.FETCH_ALL);
        metaStore.clear();
        // lenient：不是每个用例都会走这三条路径（比如直接测失败分支的用例）
        lenient().when(authorMetaRepository.findAllByBookIdIn(any())).thenAnswer(invocation -> {
            Collection<String> bookIds = invocation.getArgument(0);
            return bookIds.stream().map(metaStore::get).filter(Objects::nonNull).toList();
        });
        lenient().when(authorMetaRepository.findById(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(metaStore.get(invocation.getArgument(0))));
        lenient().when(authorMetaRepository.save(any(AuthorMeta.class))).thenAnswer(invocation -> {
            AuthorMeta saved = invocation.getArgument(0);
            metaStore.put(saved.getBookId(), saved);
            return saved;
        });
    }

    private static Novel row(String bookId) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        return novel;
    }

    private static AuthorMeta meta(String bookId) {
        AuthorMeta meta = new AuthorMeta();
        meta.setBookId(bookId);
        return meta;
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

    /** 元数据表里性别取过、评分也新鲜：直接跳过，一次线路都不碰。 */
    @Test
    void skipsBooksWhoseMetaIsFresh() {
        Novel fresh = row("1");
        AuthorMeta freshMeta = meta("1");
        freshMeta.setGender(1);
        freshMeta.setGenderCheckedTime(LocalDateTime.now());
        freshMeta.setRating(8.2);
        freshMeta.setRatingCheckedTime(LocalDateTime.now());
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(List.of(fresh));
        metaStore.put("1", freshMeta);

        BookMetaEnricher.Result result = enricher.enrich(5, "read");

        assertEquals(0, result.updated());
        assertEquals(0, result.remaining());
        verify(bookClient, never()).fetchMeta(anyString());
    }

    /** 评分到期只刷评分：性别取过一次就保留，新值不覆盖。 */
    @Test
    void keepsFirstGenderWhenOnlyRatingNeedsRefresh() {
        Novel stale = row("1");
        AuthorMeta staleMeta = meta("1");
        staleMeta.setGender(0);
        staleMeta.setGenderCheckedTime(LocalDateTime.now().minusDays(30));
        staleMeta.setRatingCheckedTime(LocalDateTime.now().minusDays(30));
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(List.of(stale));
        metaStore.put("1", staleMeta);
        when(bookClient.fetchMeta("1")).thenReturn(new ThirdPartyBookClient.BookMeta(1, 9.1));
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(stale));

        enricher.enrich(5, "read");

        // 性别只取一次：即使这次返回 1，也不覆盖首次的 0
        assertEquals(0, staleMeta.getGender());
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

    @Test
    void pendingMetaBookIdsQueriesWithStaleThreshold() {
        when(novelRepository.findBookIdsNeedingMeta(any(LocalDateTime.class), anyInt())).thenReturn(List.of("9", "8"));

        assertEquals(List.of("9", "8"), enricher.pendingMetaBookIds());

        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(novelRepository).findBookIdsNeedingMeta(captor.capture(), eq(-1));
        // 阈值 ≈ now - ratingRefreshDays（±1 分钟容忍取时差），跟着配置走，不写死天数
        LocalDateTime expected = LocalDateTime.now().minusDays(config.getRatingRefreshDays());
        assertTrue(!captor.getValue().isBefore(expected.minusMinutes(1))
                && !captor.getValue().isAfter(expected.plusMinutes(1)));
    }

    /** 只拉男频时待补清单要带频道过滤，第三方额度全给男频；全拉时传 -1 不过滤。 */
    @Test
    void pendingQueueFollowsFetchGender() {
        when(configService.getFetchGender()).thenReturn(ConfigService.FETCH_MALE);
        when(novelRepository.findBookIdsNeedingMeta(any(LocalDateTime.class), eq(1)))
                .thenReturn(List.of("9"));

        assertEquals(List.of("9"), enricher.pendingMetaBookIds());
        verify(novelRepository).findBookIdsNeedingMeta(any(LocalDateTime.class), eq(1));
    }

    @Test
    void enrichOneWritesRowsAndReportsUpdated() {
        Novel stale = row("7");
        when(bookClient.fetchMeta("7")).thenReturn(new ThirdPartyBookClient.BookMeta(0, 7.5));
        when(novelRepository.findAllByBookId("7")).thenReturn(List.of(stale));

        BookMetaEnricher.Outcome outcome = enricher.enrichOne("7");

        assertEquals(BookMetaEnricher.Outcome.UPDATED, outcome);
        assertEquals(0, stale.getAuthorGender());
        assertEquals(7.5, stale.getRating(), 0.001);
        assertNotNull(stale.getAuthorGenderCheckedTime());
        assertNotNull(stale.getRatingCheckedTime());
    }

    @Test
    void enrichOneReportsFailureWithoutTouchingRows() {
        Novel stale = row("7");
        // 失败发生在 fetchMeta，走不到落库，所以只 stub 抓取
        when(bookClient.fetchMeta("7")).thenThrow(new RuntimeException("线路 503"));

        BookMetaEnricher.Outcome outcome = enricher.enrichOne("7");

        assertEquals(BookMetaEnricher.Outcome.FAILED, outcome);
        assertNull(stale.getAuthorGenderCheckedTime());
        assertNull(stale.getRatingCheckedTime());
    }

    /** 已下架的书：在元数据表上盖章离开待补队列，不冷却线路、不写虚假性别。 */
    @Test
    void enrichOneSkipsRemovedBookAndStampsCheckedTime() {
        when(bookClient.fetchMeta("7")).thenThrow(new ItemUnavailableException("书籍已下架"));

        BookMetaEnricher.Outcome outcome = enricher.enrichOne("7");

        assertEquals(BookMetaEnricher.Outcome.SKIPPED, outcome);
        org.mockito.ArgumentCaptor<AuthorMeta> captor =
                org.mockito.ArgumentCaptor.forClass(AuthorMeta.class);
        verify(authorMetaRepository).save(captor.capture());
        assertNotNull(captor.getValue().getGenderCheckedTime());
        assertNotNull(captor.getValue().getRatingCheckedTime());
        assertNull(captor.getValue().getGender());
    }

    /** 书已掉榜（榜单行被清理掉）：元数据照样进 author_meta，重新上榜不用再花一次额度。 */
    @Test
    void keepsAuthorMetaEvenWhenRankRowIsGone() {
        when(bookClient.fetchMeta("9")).thenReturn(new ThirdPartyBookClient.BookMeta(1, 9.3));
        when(novelRepository.findAllByBookId("9")).thenReturn(List.of());

        assertEquals(BookMetaEnricher.Outcome.UPDATED, enricher.enrichOne("9"));

        org.mockito.ArgumentCaptor<AuthorMeta> captor =
                org.mockito.ArgumentCaptor.forClass(AuthorMeta.class);
        verify(authorMetaRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getGender());
        assertEquals(9.3, captor.getValue().getRating(), 0.001);
        verify(novelRepository, never()).saveAll(any());
    }

    /** 线路冷却中不是失败：不落库、不盖章，交给线程等冷却结束。 */
    @Test
    void enrichOneReportsCoolingWithoutTouchingRows() {
        when(bookClient.fetchMeta("7")).thenThrow(new LineCoolingException("第三方线路全部在冷却中"));

        assertEquals(BookMetaEnricher.Outcome.COOLDOWN, enricher.enrichOne("7"));
        verify(novelRepository, never()).findAllByBookId(anyString());
    }

    // ---- 频道兜底：第三方判未知时才用，第三方有可信值时绝不拿频道覆盖 ----

    /** 第三方 profile_gender=2（未知）+ 男频书 → 记男；评分照常落库。 */
    @Test
    void fallsBackToChannelWhenThirdPartyGenderUnknown() {
        Novel male = row("7");
        male.setGender(1);
        when(bookClient.fetchMeta("7")).thenReturn(new ThirdPartyBookClient.BookMeta(null, 8.5));
        when(novelRepository.findAllByBookId("7")).thenReturn(List.of(male));

        assertEquals(BookMetaEnricher.Outcome.UPDATED, enricher.enrichOne("7"));

        assertEquals(1, male.getAuthorGender());
        assertEquals(8.5, male.getRating(), 0.001);
        assertNotNull(male.getAuthorGenderCheckedTime());
        org.mockito.ArgumentCaptor<AuthorMeta> captor =
                org.mockito.ArgumentCaptor.forClass(AuthorMeta.class);
        verify(authorMetaRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getGender());
    }

    /** 女频书同样兜底：第三方未知 + gender=0 → 记女。 */
    @Test
    void fallsBackToChannelForFemaleChannel() {
        Novel female = row("7");
        female.setGender(0);
        when(bookClient.fetchMeta("7")).thenReturn(new ThirdPartyBookClient.BookMeta(null, 9.0));
        when(novelRepository.findAllByBookId("7")).thenReturn(List.of(female));

        enricher.enrichOne("7");

        assertEquals(0, female.getAuthorGender());
    }

    /**
     * 兜底只在第三方未知时生效：第三方给了可信值（哪怕是女作者写在男频书里），
     * 也必须原样落库，不拿频道"纠正"它——作者性别与频道本无强相关。
     */
    @Test
    void neverOverridesThirdPartyGenderWithChannel() {
        Novel maleChannel = row("7");
        maleChannel.setGender(1);
        when(bookClient.fetchMeta("7")).thenReturn(new ThirdPartyBookClient.BookMeta(0, 8.8));
        when(novelRepository.findAllByBookId("7")).thenReturn(List.of(maleChannel));

        enricher.enrichOne("7");

        assertEquals(0, maleChannel.getAuthorGender());
    }

    /** 书已掉榜（没有榜单行可取频道）：兜底无从发生，仍写未知，不猜。 */
    @Test
    void keepsUnknownWhenNoRankRowToReadChannelFrom() {
        when(bookClient.fetchMeta("9")).thenReturn(new ThirdPartyBookClient.BookMeta(null, 9.3));
        when(novelRepository.findAllByBookId("9")).thenReturn(List.of());

        enricher.enrichOne("9");

        org.mockito.ArgumentCaptor<AuthorMeta> captor =
                org.mockito.ArgumentCaptor.forClass(AuthorMeta.class);
        verify(authorMetaRepository).save(captor.capture());
        assertNull(captor.getValue().getGender());
        assertEquals(9.3, captor.getValue().getRating(), 0.001);
    }

    /** 书级失败不累计「连续失败」：3 本下架书之后仍应继续补后面的书。 */
    @Test
    void itemLevelFailureDoesNotCountTowardLineDown() {
        List<Novel> candidates = new ArrayList<>(List.of(row("1"), row("2"), row("3"), row("4"), row("5")));
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class))).thenReturn(candidates);
        when(bookClient.fetchMeta("1")).thenThrow(new ItemUnavailableException("书籍已下架"));
        when(bookClient.fetchMeta("2")).thenThrow(new ItemUnavailableException("书籍已下架"));
        when(bookClient.fetchMeta("3")).thenThrow(new ItemUnavailableException("书籍已下架"));
        when(bookClient.fetchMeta("4")).thenReturn(new ThirdPartyBookClient.BookMeta(1, 8.0));
        when(bookClient.fetchMeta("5")).thenReturn(new ThirdPartyBookClient.BookMeta(0, 7.0));
        when(novelRepository.findAllByBookId(anyString())).thenAnswer(invocation -> List.of(
                candidates.stream().filter(n -> n.getBookId().equals(invocation.getArgument(0)))
                        .findFirst().orElseThrow()));

        BookMetaEnricher.Result result = enricher.enrich(5, "read");

        assertEquals(3, result.skipped());
        assertEquals(2, result.updated());
        assertEquals(0, result.failed());
        verify(bookClient, times(5)).fetchMeta(anyString());
    }

    /** 冷却中直接收尾：不把「线路在休息」记成失败，也不继续撞后面的书。 */
    @Test
    void enrichStopsWithoutCountingCoolingAsFailure() {
        when(novelRepository.findMetaTopBooks(eq("read"), any(Pageable.class)))
                .thenReturn(new ArrayList<>(List.of(row("1"), row("2"), row("3"))));
        when(bookClient.fetchMeta("1")).thenThrow(new LineCoolingException("第三方线路全部在冷却中"));

        BookMetaEnricher.Result result = enricher.enrich(3, "read");

        assertEquals(0, result.updated());
        assertEquals(0, result.failed());
        assertEquals(0, result.skipped());
        verify(bookClient, times(1)).fetchMeta(anyString());
    }
}
