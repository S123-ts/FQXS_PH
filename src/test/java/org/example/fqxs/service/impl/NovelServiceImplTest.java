package org.example.fqxs.service.impl;

import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.NovelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NovelServiceImplTest {

    @Mock
    private NovelRepository novelRepository;

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

    /** upsert 的预读按 (categoryCode, bookId) 组合查询，一本书可同时挂在多个分类榜。 */
    @Test
    void upsertLoadsExistingRowsByCategoryAndBookIdInOneQuery() {
        Novel existing = novel("1", "旧标题", 10L);
        existing.setIsBlocked(true);
        when(novelRepository.findByCategoryCodeAndBookIdIn(eq("M_WESTERN_FANTASY"), any(Collection.class)))
                .thenReturn(List.of(existing));

        NovelService.UpsertSummary summary = service.saveOrUpdateNovelList(List.of(
                info("1", "新标题"), info("2", "另一本"), info("3", "再来一本")),
                CategoryCatalog.M_WESTERN_FANTASY, false);

        assertEquals(new NovelService.UpsertSummary(2, 1, 0), summary);
        verify(novelRepository, times(1)).findByCategoryCodeAndBookIdIn(anyString(), any(Collection.class));
        verify(novelRepository, never()).findAllByBookId(any());
        verify(novelRepository, never()).deleteStaleByCategoryCode(any(), any(Collection.class));

        ArgumentCaptor<List<Novel>> saved = ArgumentCaptor.forClass(List.class);
        verify(novelRepository).saveAll(saved.capture());
        assertEquals(3, saved.getValue().size());

        Novel merged = saved.getValue().stream().filter(n -> "1".equals(n.getBookId())).findFirst().orElseThrow();
        assertEquals("新标题", merged.getTitle());
        assertEquals("M_WESTERN_FANTASY", merged.getCategoryCode());
        assertEquals("西方奇幻", merged.getCategoryName());
        assertEquals(1, merged.getGender());
        assertTrue(merged.getIsBlocked(), "重新爬取不能清掉拉黑状态");

        Novel fresh = saved.getValue().stream().filter(n -> "2".equals(n.getBookId())).findFirst().orElseThrow();
        assertFalse(fresh.getIsBlocked());
    }

    /** 女频分类的行必须带上 gender=0 与自己的 code，两个榜的行互不覆盖。 */
    @Test
    void femaleCategoryRowsCarryTheirOwnIdentity() {
        when(novelRepository.findByCategoryCodeAndBookIdIn(eq("F_ERA"), any(Collection.class)))
                .thenReturn(List.of());

        service.saveOrUpdateNovelList(List.of(info("9", "年代文")), CategoryCatalog.F_ERA, false);

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
                List.of(info("", "无 ID 甲"), info(null, "无 ID 乙")), CategoryCatalog.M_WESTERN_FANTASY, false);

        assertEquals(2, summary.skipped());
        verify(novelRepository, never()).findByCategoryCodeAndBookIdIn(any(), any(Collection.class));
        verify(novelRepository, never()).saveAll(any(List.class));
    }

    @Test
    void blankListShortCircuits() {
        assertEquals(NovelService.UpsertSummary.EMPTY,
                service.saveOrUpdateNovelList(List.of(), CategoryCatalog.M_WESTERN_FANTASY, true));
        verifyNoInteractions(novelRepository);
    }

    @Test
    void completeListPrunesBooksThatFellOffTheRanking() {
        when(novelRepository.findByCategoryCodeAndBookIdIn(any(), any(Collection.class))).thenReturn(List.of());
        when(novelRepository.deleteStaleByCategoryCode(any(), any(Collection.class))).thenReturn(7);

        service.saveOrUpdateNovelList(List.of(info("1", "在册"), info("2", "在册")),
                CategoryCatalog.M_WESTERN_FANTASY, true);

        verify(novelRepository).deleteStaleByCategoryCode(eq("M_WESTERN_FANTASY"), eq(List.of("1", "2")));
    }

    @Test
    void allBlankBookIdsStillNeverPrune() {
        service.saveOrUpdateNovelList(List.of(info("", "无 ID")), CategoryCatalog.M_WESTERN_FANTASY, true);

        verify(novelRepository, never()).deleteStaleByCategoryCode(any(), any(Collection.class));
    }

    @Test
    void blockedBooksAreHiddenAndOrderFollowsReaderCount() {
        Novel low = novel("1", "少人读", 100L);
        Novel blocked = novel("2", "已拉黑", 9999L);
        blocked.setIsBlocked(true);
        Novel mid = novel("3", "中等热度", 300L);
        when(novelRepository.findAll()).thenReturn(List.of(low, blocked, mid));

        List<Novel> result = service.findAllBooksFiltered(List.of(), null, null, false);
        assertEquals(List.of("3", "1"), result.stream().map(Novel::getBookId).toList());
    }

    /** 拉黑必须有出口，否则只能进数据库改：blockedOnly 就是那个“已拉黑”列表，按拉黑时刻新→旧。 */
    @Test
    void blockedOnlyViewSortsByNewestBlockAndParksLegacyRowsLast() {
        Novel legacy = novel("1", "没有拉黑时刻的历史行", 9999L);
        legacy.setIsBlocked(true);
        Novel earlyBlock = novel("2", "先拉的", 1L);
        earlyBlock.setIsBlocked(true);
        earlyBlock.setBlockedTime(LocalDateTime.of(2026, 9, 18, 10, 0));
        Novel lateBlock = novel("3", "后拉的", 2L);
        lateBlock.setIsBlocked(true);
        lateBlock.setBlockedTime(LocalDateTime.of(2026, 9, 19, 10, 0));
        when(novelRepository.findAll()).thenReturn(List.of(legacy, earlyBlock, lateBlock));

        List<Novel> result = service.findAllBooksFiltered(List.of(), null, null, true);
        assertEquals(List.of("3", "2", "1"), result.stream().map(Novel::getBookId).toList());
    }

    /** blockedOnly 与分类 codes 是交集：只看“所选分类里的拉黑行”，其他分类的拉黑行不得出现——
        前端“只看已拉黑”永远带着分类范围发请求，这条交集语义没有任何其他用例钉住。 */
    @Test
    void blockedOnlyViewIntersectsWithSelectedCategories() {
        Novel blockedInSelected = novel("1", "所选分类的拉黑行", 10L);
        blockedInSelected.setIsBlocked(true);
        Novel visibleInSelected = novel("2", "所选分类的正常行", 20L);
        when(novelRepository.findByCategoryCodeIn(List.of("M_WESTERN_FANTASY")))
                .thenReturn(List.of(blockedInSelected, visibleInSelected));

        List<Novel> result = service.findAllBooksFiltered(List.of("M_WESTERN_FANTASY"), null, null, true);

        assertEquals(List.of("1"), result.stream().map(Novel::getBookId).toList());
    }

    @Test
    void wordRangeFilterUnderstandsWanAndRawNumbers() {
        Novel small = novel("1", "八十万", 1L);
        small.setWordCount("80万");
        Novel medium = novel("2", "一百二十万", 1L);
        medium.setWordCount("120万");
        Novel raw = novel("3", "原始字数", 1L);
        raw.setWordCount("1500000");
        when(novelRepository.findAll()).thenReturn(List.of(small, medium, raw));

        assertEquals(List.of("2", "3"),
                service.findAllBooksFiltered(null, "100w以上", null, false).stream().map(Novel::getBookId).toList());
        assertEquals(List.of("1"),
                service.findAllBooksFiltered(null, "100w以内", null, false).stream().map(Novel::getBookId).toList());
    }

    @Test
    void statusFilterUsesTheNormalizedLabel() {
        Novel ongoing = novel("1", "连载中", 1L);
        ongoing.setStatus("连载中");
        Novel done = novel("2", "已完结", 1L);
        done.setStatus("已完结");
        when(novelRepository.findByCategoryCodeIn(List.of("M_WESTERN_FANTASY"))).thenReturn(List.of(ongoing, done));

        assertEquals(List.of("2"),
                service.findAllBooksFiltered(List.of("M_WESTERN_FANTASY"), null, "已完结", false)
                        .stream().map(Novel::getBookId).toList());
        verify(novelRepository).findByCategoryCodeIn(List.of("M_WESTERN_FANTASY"));
    }

    @Test
    void databaseOutagesPropagateInsteadOfLookingLikeEmptyData() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("数据库未启动");
        when(novelRepository.findAll()).thenThrow(down);
        when(novelRepository.findByCategoryCodeOrderByRankNumeric(any())).thenThrow(down);

        assertThrows(DataAccessResourceFailureException.class, () -> service.findAllNovels());
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.findNovelsByCategoryCode("M_WESTERN_FANTASY"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.findAllBooksFiltered(null, null, null, false));
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
}
