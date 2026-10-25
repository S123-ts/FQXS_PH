package org.example.fqxs.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.AuthorMeta;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.entity.NovelAiVerdict;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.repository.AuthorMetaRepository;
import org.example.fqxs.repository.NovelAiVerdictRepository;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NovelServiceImplTest {

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private NovelAiVerdictRepository verdictRepository;

    @Mock
    private AuthorMetaRepository authorMetaRepository;

    /** 用真实解码器：干净文本要原样通过、混淆文本要还原成汉字，打桩会掩盖映射错误。 */
    @Spy
    private PuaDecoder puaDecoder = new PuaDecoder(new ObjectMapper());

    @InjectMocks
    private NovelServiceImpl service;

    private NovelInfo info(String bookId, String title) {
        return NovelInfo.builder().bookId(bookId).title(title).rank("1").build();
    }

    private Novel novel(String bookId, String title, Long readers) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        novel.setTitle(title);
        novel.setRank("1");
        novel.setReadCountRaw(readers);
        novel.setIsBlocked(false);
        return novel;
    }

    /** 主表按明细聚合：GLM 判 AI、ERNIE 后判非 AI，主表不能被后写入的 ERNIE 覆盖。 */
    @Test
    void aggregateKeepsAiWhenAnotherEngineReportsHuman() {
        Novel row = novel("1", "书", 100L);
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(row));
        when(verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(List.of("1"))).thenReturn(List.of(
                verdict("qianfan-ernie-speed", "ernie-4.5-turbo-32k", 1, 20.0,
                        LocalDateTime.of(2026, 10, 23, 21, 33)),
                verdict("glm-4-flash", "glm-4-flash", 2, 95.0,
                        LocalDateTime.of(2026, 10, 23, 21, 32))));

        service.applyFreeVerdict("1", 1, 20.0, "qianfan-ernie-speed");

        assertEquals(2, row.getAiFlag());
        assertEquals(95.0, row.getAiScore(), 0.001);
        assertEquals("glm-4-flash", row.getAiEngine());
    }

    /** 手动逐章命中持久：后来同型号初筛判非 AI，主表仍是 AI。 */
    @Test
    void manualVerifyHitSurvivesLaterHumanSweep() {
        Novel row = novel("1", "书", 100L);
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(row));
        when(verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(List.of("1"))).thenReturn(List.of(
                verdict("glm-4-flash", "glm-4-flash", 1, 10.0,
                        LocalDateTime.of(2026, 10, 24, 10, 0)),
                verdict("glm-4-flash", "glm-4-flash#verify", 2, 80.0,
                        LocalDateTime.of(2026, 10, 23, 10, 0))));

        service.applyFreeVerdict("1", 1, 10.0, "glm-4-flash");

        assertEquals(2, row.getAiFlag());
        assertEquals(80.0, row.getAiScore(), 0.001);
    }

    /** 逐章未命中的完成结论不能覆盖同型号的初筛 AI。 */
    @Test
    void verifyMissDoesNotOverrideSweepAi() {
        Novel row = novel("1", "书", 100L);
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(row));
        when(verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(List.of("1"))).thenReturn(List.of(
                verdict("glm-4-flash", "glm-4-flash#verify", 1, 12.0,
                        LocalDateTime.of(2026, 10, 24, 10, 0)),
                verdict("glm-4-flash", "glm-4-flash", 2, 90.0,
                        LocalDateTime.of(2026, 10, 23, 10, 0))));

        service.applyManualVerifyVerdict("1", "glm-4-flash", 1, 10, 12.0, false);

        assertEquals(2, row.getAiFlag());
        assertEquals(90.0, row.getAiScore(), 0.001);
    }

    /** 启动回填：把所有出现过明细的书的旧口径主表值按型号聚合重算。 */
    @Test
    void startupAggregateRecomputesEveryJudgedBook() {
        Novel row = novel("1", "书", 100L);
        when(verdictRepository.findDistinctBookIds()).thenReturn(List.of("1"));
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(row));
        when(verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(List.of("1"))).thenReturn(List.of(
                verdict("glm-4-flash", "glm-4-flash", 2, 90.0,
                        LocalDateTime.of(2026, 10, 23, 10, 0))));

        assertEquals(1, service.recomputeAiAggregates());
        assertEquals(2, row.getAiFlag());
        assertEquals(90.0, row.getAiScore(), 0.001);
    }

    /** 明细为空（历史数据/并发清理）时退回调用方给的单次结论。 */
    @Test
    void aggregateFallsBackToSingleVerdictWhenNoDetailRows() {
        Novel row = novel("1", "书", 100L);
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(row));
        when(verdictRepository.findByBookIdInOrderByCheckedTimeDescIdDesc(List.of("1"))).thenReturn(List.of());

        service.applyFreeVerdict("1", 3, 55.0, "glm-4-flash");

        assertEquals(3, row.getAiFlag());
        assertEquals(55.0, row.getAiScore(), 0.001);
    }

    private NovelAiVerdict verdict(String engine, String model, int flag, double score, LocalDateTime time) {
        NovelAiVerdict row = new NovelAiVerdict();
        row.setEngine(engine);
        row.setModel(model);
        row.setAiFlag(flag);
        row.setAiScore(score);
        row.setCheckedTime(time);
        row.setChaptersChecked(model.endsWith("#verify") ? 5 : 0);
        return row;
    }

    /** upsert 的预读按 (categoryCode, rankType, bookId) 组合查询，一本书可同时挂在多个分类榜。 */
    @Test
    void upsertLoadsExistingRowsByCategoryAndBookIdInOneQuery() {
        Novel existing = novel("1", "旧标题", 10L);
        existing.setIsBlocked(true);
        when(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(
                eq("M_WESTERN_FANTASY"), eq("read"), any(Collection.class)))
                .thenReturn(List.of(existing));

        NovelService.UpsertSummary summary = service.saveOrUpdateNovelList(List.of(
                info("1", "新标题"), info("2", "另一本"), info("3", "再来一本")),
                CategoryCatalog.M_WESTERN_FANTASY, "read", false);

        assertEquals(new NovelService.UpsertSummary(2, 1, 0), summary);
        verify(novelRepository, times(1)).findByCategoryCodeAndRankTypeAndBookIdIn(
                anyString(), any(), any(Collection.class));
        verify(novelRepository, never()).findAllByBookId(any());
        verify(novelRepository, never()).deleteStaleByCategoryCodeAndRankType(any(), any(), any(Collection.class));

        ArgumentCaptor<List<Novel>> saved = ArgumentCaptor.forClass(List.class);
        verify(novelRepository).saveAll(saved.capture());
        assertEquals(3, saved.getValue().size());

        Novel merged = saved.getValue().stream().filter(n -> "1".equals(n.getBookId())).findFirst().orElseThrow();
        assertEquals("新标题", merged.getTitle());
        assertEquals("M_WESTERN_FANTASY", merged.getCategoryCode());
        assertEquals("西方奇幻", merged.getCategoryName());
        assertEquals(1, merged.getGender());
        assertEquals("read", merged.getRankType(), "写入哪个榜单分区由入参 rankType 决定");
        assertTrue(merged.getIsBlocked(), "重新爬取不能清掉拉黑状态");

        Novel fresh = saved.getValue().stream().filter(n -> "2".equals(n.getBookId())).findFirst().orElseThrow();
        assertFalse(fresh.getIsBlocked());
    }

    /**
     * 上游把书名、作者、简介也替换成了私用区字符：前端靠番茄字体把 U+E3E8 画成汉字，
     * 页面看着正常，但库里与接口返回都是残缺文本。入库是唯一文本写入口，必须在这里还原，
     * 否则搜索（中文关键词对 PUA）、导出与 AI 提示词里的书名全都不可用。
     */
    @Test
    void upsertDecodesPuaTextBeforePersisting() {
        when(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(
                eq("M_WESTERN_FANTASY"), eq("read"), any(Collection.class)))
                .thenReturn(List.of());

        // U+E3E8 是映射表首项，固定还原成 "D"，用它做锚点避免测试自证
        NovelInfo polluted = NovelInfo.builder()
                .bookId("77")
                .rank("1")
                .title("\uE3E8书")
                .author("\uE3E8者")
                .description("简介\uE3E8")
                .lastChapter("\uE3E8章")
                .build();

        service.saveOrUpdateNovelList(List.of(polluted), CategoryCatalog.M_WESTERN_FANTASY, "read", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Novel>> saved = ArgumentCaptor.forClass(List.class);
        verify(novelRepository).saveAll(saved.capture());
        Novel row = saved.getValue().get(0);

        assertEquals("D书", row.getTitle());
        assertEquals("D者", row.getAuthor());
        assertEquals("简介D", row.getDescription());
        assertEquals("D章", row.getLastChapter());
        assertFalse(PuaDecoder.containsPua(row.getTitle()), "入库后不能再残留私用区字符");
    }

    /**
     * 拉榜回填：author_meta 里已有的作者信息直接抄进新行。
     *
     * <p>防的是「书掉榜被清理、过几天又上榜」这种最常见的重复取数：元数据明明还在，
     * 不回填的话页面先显示「未知」，补齐线程还得再花一次第三方额度。</p>
     */
    @Test
    void backfillsAuthorMetaOntoNewRows() {
        when(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(
                anyString(), any(), any(Collection.class))).thenReturn(List.of());
        AuthorMeta known = new AuthorMeta();
        known.setBookId("1");
        known.setGender(1);
        known.setGenderCheckedTime(LocalDateTime.now().minusDays(3));
        known.setRating(9.4);
        known.setRatingCheckedTime(LocalDateTime.now().minusDays(3));
        when(authorMetaRepository.findAllByBookIdIn(any())).thenReturn(List.of(known));

        service.saveOrUpdateNovelList(List.of(info("1", "重新上榜的老书")),
                CategoryCatalog.M_WESTERN_FANTASY, "read", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Novel>> saved = ArgumentCaptor.forClass(List.class);
        verify(novelRepository).saveAll(saved.capture());
        Novel row = saved.getValue().get(0);
        assertEquals(1, row.getAuthorGender(), "性别从元数据表回填，不该留空");
        assertNotNull(row.getAuthorGenderCheckedTime());
        assertEquals(9.4, row.getRating(), 0.001);
        assertEquals("重新上榜的老书", row.getTitle(), "榜单快照仍以本次抓取为准");
    }

    /** 女频分类的行必须带上 gender=0 与自己的 code，两个榜的行互不覆盖。 */
    @Test
    void femaleCategoryRowsCarryTheirOwnIdentity() {
        when(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(
                eq("F_ERA"), eq("read"), any(Collection.class)))
                .thenReturn(List.of());

        service.saveOrUpdateNovelList(List.of(info("9", "年代文")), CategoryCatalog.F_ERA, "read", false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Novel>> saved = ArgumentCaptor.forClass(List.class);
        verify(novelRepository).saveAll(saved.capture());
        Novel row = saved.getValue().get(0);
        assertEquals("F_ERA", row.getCategoryCode());
        assertEquals("年代", row.getCategoryName());
        assertEquals(0, row.getGender());
    }

    @Test
    void recordsWithoutBookIdAreSkippedRatherThanMergedIntoOneRow() {
        NovelService.UpsertSummary summary = service.saveOrUpdateNovelList(
                List.of(info("", "无 ID 甲"), info(null, "无 ID 乙")), CategoryCatalog.M_WESTERN_FANTASY,
                "read", false);

        assertEquals(2, summary.skipped());
        verify(novelRepository, never()).findByCategoryCodeAndRankTypeAndBookIdIn(
                any(), any(), any(Collection.class));
        verify(novelRepository, never()).saveAll(any(List.class));
    }

    @Test
    void blankListShortCircuits() {
        assertEquals(NovelService.UpsertSummary.EMPTY,
                service.saveOrUpdateNovelList(List.of(), CategoryCatalog.M_WESTERN_FANTASY, "read", true));
        verifyNoInteractions(novelRepository);
    }

    @Test
    void completeListPrunesBooksThatFellOffTheRanking() {
        when(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(
                any(), any(), any(Collection.class))).thenReturn(List.of());
        when(novelRepository.deleteStaleByCategoryCodeAndRankType(any(), any(), any(Collection.class)))
                .thenReturn(7);

        service.saveOrUpdateNovelList(List.of(info("1", "在册"), info("2", "在册")),
                CategoryCatalog.M_WESTERN_FANTASY, "read", true);

        verify(novelRepository).deleteStaleByCategoryCodeAndRankType(
                eq("M_WESTERN_FANTASY"), eq("read"), eq(List.of("1", "2")));
    }

    @Test
    void allBlankBookIdsStillNeverPrune() {
        service.saveOrUpdateNovelList(List.of(info("", "无 ID")), CategoryCatalog.M_WESTERN_FANTASY, "read", true);

        verify(novelRepository, never()).deleteStaleByCategoryCodeAndRankType(any(), any(), any(Collection.class));
    }

    /** 默认视图：热度排序与“排除拉黑”都下推给 SQL，服务层只做参数归一。 */
    @Test
    void defaultViewPushesHeatSortAndUnblockedFilter() {
        when(novelRepository.pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("3", "中等热度", 300L), novel("1", "少人读", 100L))));

        Page<Novel> result = service.findAllBooksFiltered(List.of(), null, null, false, false, "read", 1, 30,
                null, null);

        assertEquals(List.of("3", "1"), result.getContent().stream().map(Novel::getBookId).toList());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(novelRepository).pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), isNull(), eq(""), isNull(), pageable.capture());
        assertEquals(Sort.Direction.DESC,
                pageable.getValue().getSort().getOrderFor("readCountRaw").getDirection());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(30, pageable.getValue().getPageSize());
    }

    /** 评分下限下推到 SQL 并钳到 0–10：前端允许留空，服务层不再接受越界值。 */
    @Test
    void minRatingIsClampedAndPushedDown() {
        when(novelRepository.pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), isNull(), eq(""), eq(10.0), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.findAllBooksFiltered(List.of(), null, null, false, false, "read", 1, 30, null, 12.5);

        verify(novelRepository).pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), isNull(), eq(""), eq(10.0), any(Pageable.class));
    }

    /** 拉黑必须有出口，否则只能进数据库改：blockedOnly 就是那个“已拉黑”列表，按拉黑时刻新→旧。 */
    /** “已拉黑”列表按拉黑时刻新→旧；没有时刻的历史行排最后（SQL 的 nullsLast）。 */
    @Test
    void blockedOnlyViewSortsByNewestBlockAndParksLegacyRowsLast() {
        when(novelRepository.pageAllBooks(eq("read"), eq(true), eq(false), eq(""),
                isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("3", "后拉的", 2L), novel("2", "先拉的", 1L),
                        novel("1", "没有拉黑时刻的历史行", 9999L))));

        Page<Novel> result = service.findAllBooksFiltered(List.of(), null, null, true, false, "read", 1, 30, null, null);

        assertEquals(List.of("3", "2", "1"), result.getContent().stream().map(Novel::getBookId).toList());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(novelRepository).pageAllBooks(eq("read"), eq(true), eq(false), eq(""),
                isNull(), isNull(), eq(""), isNull(), pageable.capture());
        Sort.Order byBlocked = pageable.getValue().getSort().getOrderFor("blockedTime");
        assertEquals(Sort.Direction.DESC, byBlocked.getDirection());
        assertEquals(Sort.NullHandling.NULLS_LAST, byBlocked.getNullHandling());
    }

    /** blockedOnly 与分类 codes 是交集：只看“所选分类里的拉黑行”，其他分类的拉黑行不得出现——
        前端“只看已拉黑”永远带着分类范围发请求，这条交集语义没有任何其他用例钉住。 */
    @Test
    void blockedOnlyViewIntersectsWithSelectedCategories() {
        when(novelRepository.pageAllBooksByCodes(eq(List.of("M_WESTERN_FANTASY")), eq("read"),
                eq(true), eq(false), eq(""), isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("1", "所选分类的拉黑行", 10L))));

        Page<Novel> result = service.findAllBooksFiltered(
                List.of("M_WESTERN_FANTASY"), null, null, true, false, "read", 1, 30, null, null);

        assertEquals(List.of("1"), result.getContent().stream().map(Novel::getBookId).toList());
        verify(novelRepository).pageAllBooksByCodes(eq(List.of("M_WESTERN_FANTASY")), eq("read"),
                eq(true), eq(false), eq(""), isNull(), isNull(), eq(""), isNull(), any(Pageable.class));
    }

    /** 字数档位转成开/闭区间：100w以上 = >100，100w以内 = <=100。 */
    @Test
    void wordRangeIsTurnedIntoNumericBounds() {
        when(novelRepository.pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                eq(100.0), isNull(), eq(""), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(novelRepository.pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), eq(100.0), eq(""), isNull(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.findAllBooksFiltered(null, "100w以上", null, false, false, "read", 1, 30, null, null);
        service.findAllBooksFiltered(null, "100w以内", null, false, false, "read", 1, 30, null, null);

        verify(novelRepository).pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                eq(100.0), isNull(), eq(""), isNull(), any(Pageable.class));
        verify(novelRepository).pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), eq(100.0), eq(""), isNull(), any(Pageable.class));
    }

    @Test
    void statusFilterIsPassedThrough() {
        when(novelRepository.pageAllBooksByCodes(eq(List.of("M_WESTERN_FANTASY")), eq("read"),
                eq(false), eq(false), eq("已完结"), isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("2", "已完结", 1L))));

        Page<Novel> result = service.findAllBooksFiltered(
                List.of("M_WESTERN_FANTASY"), null, "已完结", false, false, "read", 1, 30, null, null);

        assertEquals(List.of("2"), result.getContent().stream().map(Novel::getBookId).toList());
        verify(novelRepository).pageAllBooksByCodes(eq(List.of("M_WESTERN_FANTASY")), eq("read"),
                eq(false), eq(false), eq("已完结"), isNull(), isNull(), eq(""), isNull(), any(Pageable.class));
    }

    @Test
    void databaseOutagesPropagateInsteadOfLookingLikeEmptyData() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("数据库未启动");
        when(novelRepository.findAll()).thenThrow(down);
        when(novelRepository.findByCategoryCodeOrderByRankNumeric(any())).thenThrow(down);
        when(novelRepository.pageAllBooks(any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenThrow(down);

        assertThrows(DataAccessResourceFailureException.class, () -> service.findAllNovels());
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.findNovelsByCategoryCode("M_WESTERN_FANTASY"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.findAllBooksFiltered(null, null, null, false, false, "read", 1, 30, null, null));
    }

    @Test
    void setBlockedRejectsBlankAndUnknownIds() {
        assertThrows(IllegalArgumentException.class, () -> service.setBlocked("  ", true));
        when(novelRepository.findAllByBookId("missing")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.setBlocked("missing", true));
    }

    /** 同一本书可挂多个分类榜：拉黑必须把它的所有行一起开关，否则换个分类又看到了。 */
    @Test
    void setBlockedTogglesEveryRowOfTheBook() {
        Novel maleRow = novel("1", "科幻书", 1L);
        maleRow.setCategoryCode("M_SCI_FI");
        Novel femaleRow = novel("1", "科幻书", 8L);
        femaleRow.setCategoryCode("F_SCI_FI");
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(maleRow, femaleRow));

        service.setBlocked("1", true);
        assertTrue(maleRow.getIsBlocked());
        assertTrue(femaleRow.getIsBlocked());
        assertNotNull(maleRow.getBlockedTime(), "拉黑时刻是“已拉黑”列表的排序依据");
        assertNotNull(femaleRow.getBlockedTime());
        verify(novelRepository).saveAll(List.of(maleRow, femaleRow));

        service.setBlocked("1", false);
        assertFalse(maleRow.getIsBlocked());
        assertFalse(femaleRow.getIsBlocked());
        assertNull(maleRow.getBlockedTime(), "恢复后不该留下上一次的拉黑时刻");
        assertNull(femaleRow.getBlockedTime());
        verify(novelRepository, times(2)).saveAll(List.of(maleRow, femaleRow));
    }

    /** 关注不是排除标记：不勾「只看关注」时已关注的行照常出现；勾上后只留关注行且后关注的在前。 */
    @Test
    void watchedOnlyViewFiltersAndSortsWithoutHidingWatchedRowsByDefault() {
        when(novelRepository.pageAllBooks(eq("read"), eq(false), eq(false), eq(""),
                isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("1", "没关注", 500L),
                        novel("2", "先关注的", 100L), novel("3", "后关注的", 50L))));
        when(novelRepository.pageAllBooks(eq("read"), eq(false), eq(true), eq(""),
                isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("3", "后关注的", 50L), novel("2", "先关注的", 100L))));

        Page<Novel> all = service.findAllBooksFiltered(List.of(), null, null, false, false, "read", 1, 30, null, null);
        assertEquals(3, all.getTotalElements(), "默认列表不隐藏关注行");

        Page<Novel> watched = service.findAllBooksFiltered(List.of(), null, null, false, true, "read", 1, 30, null, null);
        assertEquals(List.of("3", "2"), watched.getContent().stream().map(Novel::getBookId).toList());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(novelRepository).pageAllBooks(eq("read"), eq(false), eq(true), eq(""),
                isNull(), isNull(), eq(""), isNull(), pageable.capture());
        Sort.Order byWatched = pageable.getValue().getSort().getOrderFor("watchedTime");
        assertEquals(Sort.Direction.DESC, byWatched.getDirection());
        assertEquals(Sort.NullHandling.NULLS_LAST, byWatched.getNullHandling());
    }

    /** 两个筛选同时勾选是按交集：既关注又被拉黑才出现。 */
    @Test
    void watchedOnlyIntersectsWithBlockedOnly() {
        when(novelRepository.pageAllBooks(eq("read"), eq(true), eq(true), eq(""),
                isNull(), isNull(), eq(""), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(novel("3", "又关注又拉黑", 30L))));

        Page<Novel> result = service.findAllBooksFiltered(List.of(), null, null, true, true, "read", 1, 30, null, null);

        assertEquals(List.of("3"), result.getContent().stream().map(Novel::getBookId).toList());
        verify(novelRepository).pageAllBooks(eq("read"), eq(true), eq(true), eq(""),
                isNull(), isNull(), eq(""), isNull(), any(Pageable.class));
    }

    /** 关注与拉黑同构：同一本书的全部行一起开关，取消时清空关注时刻。 */
    @Test
    void setWatchedTogglesEveryRowOfTheBook() {
        Novel maleRow = novel("1", "科幻书", 1L);
        maleRow.setCategoryCode("M_SCI_FI");
        Novel femaleRow = novel("1", "科幻书", 8L);
        femaleRow.setCategoryCode("F_SCI_FI");
        when(novelRepository.findAllByBookId("1")).thenReturn(List.of(maleRow, femaleRow));

        service.setWatched("1", true);
        assertTrue(maleRow.getIsWatched());
        assertTrue(femaleRow.getIsWatched());
        assertNotNull(maleRow.getWatchedTime(), "关注时刻是「只看关注」的排序依据");
        verify(novelRepository).saveAll(List.of(maleRow, femaleRow));

        service.setWatched("1", false);
        assertFalse(maleRow.getIsWatched());
        assertFalse(femaleRow.getIsWatched());
        assertNull(maleRow.getWatchedTime(), "取消关注后不该留下上一次的关注时刻");
        verify(novelRepository, times(2)).saveAll(List.of(maleRow, femaleRow));
    }

    @Test
    void setWatchedRejectsBlankAndUnknownIds() {
        assertThrows(IllegalArgumentException.class, () -> service.setWatched("  ", true));
        when(novelRepository.findAllByBookId("missing")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.setWatched("missing", true));
    }
}
