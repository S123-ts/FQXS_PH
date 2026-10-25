package org.example.fqxs.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.client.FanqieApiClient;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.model.RankListResult;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.ConfigService;
import org.example.fqxs.service.NovelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RankServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private FanqieApiClient apiClient;

    @Mock
    private NovelService novelService;

    @Mock
    private ConfigService configService;

    @Mock
    private NovelRepository novelRepository;

    private RankServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RankServiceImpl(apiClient, new FanqieConfig(), novelService, configService,
                novelRepository);
        when(configService.getActiveRankType()).thenReturn("read");
    }

    private JsonNode rankPayload(int books) throws Exception {
        return payload(books, null);
    }

    private JsonNode payload(int books, Integer totalNum) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= books; i++) {
            if (i > 1) {
                items.append(',');
            }
            items.append("{\"book_id\":\"b").append(i)
                    .append("\",\"book_name\":\"书名").append(i)
                    .append("\",\"read_count\":\"").append(i * 10000L)
                    .append("\",\"word_number\":\"").append(i * 100000L).append("\"}");
        }
        String total = totalNum == null ? "" : ",\"total_num\":" + totalNum;
        return MAPPER.readTree("{\"code\":0,\"data\":{\"book_list\":[" + items + "]" + total + "}}");
    }

    @Test
    void parsesBookListAndNumbersRanksFromThePageOffset() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(3));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(3, 0, 0));

        RankListResult result = service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 2, 30);

        List<NovelInfo> novels = result.novels();
        assertEquals(3, novels.size());
        assertTrue(result.dbSaved());
        assertEquals("31", novels.get(0).getRank(), "第 2 页首名应接着第 1 页");
        assertEquals("b1", novels.get(0).getBookId());
        assertEquals("书名1", novels.get(0).getTitle());
        assertEquals("1.0万", novels.get(0).getReadCount());
        assertEquals(10000L, novels.get(0).getReadCountRaw());
        assertEquals("西方奇幻", novels.get(0).getCategoryName());
        assertEquals("M_WESTERN_FANTASY", novels.get(0).getCategoryCode());
        assertEquals(1, novels.get(0).getGender());
        verify(novelService).saveOrUpdateNovelList(anyList(), eq(CategoryCatalog.M_WESTERN_FANTASY),
                any(), anyBoolean());
    }

    /** 榜单寻址三元组必须来自分类目录与 activeRankType，缺一不可。 */
    @Test
    void urlUsesTheCategoryIdentityAndTheActiveRankTypeMold() throws Exception {
        when(configService.getActiveRankType()).thenReturn("new");
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(1));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(1, 0, 0));

        service.getRankList(CategoryCatalog.F_SCI_FI, 1, 100);

        // 女频科幻末世 + 新书榜：gender=0, category_id=8, rankMold=1（由 rankType=new 推导）
        verify(apiClient).fetchRankUrl(eq(0), eq(8), eq(1), eq(1), eq(100));
        NovelInfo novel = captureSingleNovel();
        assertEquals("F_SCI_FI", novel.getCategoryCode());
        assertEquals("科幻末世", novel.getCategoryName());
        assertEquals(0, novel.getGender());
    }

    @SuppressWarnings("unchecked")
    private NovelInfo captureSingleNovel() {
        ArgumentCaptor<List<NovelInfo>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(novelService).saveOrUpdateNovelList(captor.capture(), any(), any(), anyBoolean());
        return captor.getValue().get(0);
    }

    @Test
    void pageSizeIsClampedBeforeHittingTheUpstreamClient() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(1));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(1, 0, 0));

        service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 9999);

        verify(apiClient).fetchRankUrl(eq(1), eq(1141), eq(2), eq(1), eq(FanqieConfig.MAX_PAGE_SIZE));
    }

    @Test
    void crawlFailureIsReportedInsteadOfAnEmptySuccess() {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new CrawlException("上游返回 403，请检查接口参数或是否被风控"));

        CrawlException error = assertThrows(CrawlException.class,
                () -> service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30));
        assertTrue(error.getMessage().contains("403"), error.getMessage());
        verify(novelService, never()).saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean());
    }

    /** 每个分类恒定 100 条，抓到 0 条只可能是上游换字段或被风控，不能当成“榜是空的”。 */
    @Test
    void emptyBoardIsReportedAsAFailureRatherThanAnEmptySuccess() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(payload(0, 100));

        CrawlException error = assertThrows(CrawlException.class,
                () -> service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30));

        assertTrue(error.getMessage().contains("0 条"), error.getMessage());
        assertTrue(error.getMessage().contains("total_num=100"), error.getMessage());
        verify(novelService, never()).saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean());
    }

    @Test
    void unexpectedResponseShapeIsReportedRatherThanLookingEmpty() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(MAPPER.readTree("{\"code\":7,\"msg\":\"风控\"}"));

        assertThrows(CrawlException.class,
                () -> service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30));
    }

    @Test
    void databaseWriteFailureStillReturnsTheCrawledPage() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(2));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("数据库未启动"));

        RankListResult result = service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30);

        assertEquals(2, result.novels().size());
        assertFalse(result.dbSaved(), "落库失败必须让前端知道");
    }

    /** 分类视图的新书徽章判据是 create_time：写库成功后必须按 bookId 回填进 NovelInfo。 */
    @Test
    void persistBackfillsFirstSeenTimeForTheNewBadge() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(2));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(2, 0, 0));
        Novel row = new Novel();
        row.setBookId("b1");
        row.setCreateTime(LocalDateTime.of(2026, 10, 25, 12, 30, 0));
        when(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn(any(), any(), any()))
                .thenReturn(List.of(row));

        RankListResult result = service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30);

        assertEquals("2026-10-25 12:30:00", result.novels().get(0).getCreateTime(),
                "回填格式必须与 Novel.createTime 的 JSON 序列化保持一致");
        assertNull(result.novels().get(1).getCreateTime(), "没查到入库时刻的行留空，前端不亮徽章");
    }

    /** 库挂了时没有入库时刻可回填，也不能为此再查一次库。 */
    @Test
    void databaseWriteFailureLeavesFirstSeenTimeEmpty() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(2));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("数据库未启动"));

        RankListResult result = service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30);

        assertFalse(result.dbSaved());
        assertNull(result.novels().get(0).getCreateTime());
        verify(novelRepository, never()).findByCategoryCodeAndRankTypeAndBookIdIn(any(), any(), any());
    }

    /** 一键获取（全量）按「请求数」判完整：70 本就是新的整榜口径，71–100 名应按掉榜清掉。 */
    @Test
    void fullFetchTreatsRequestedSizeAsWholeList() throws Exception {
        when(configService.getDisplayedCategories()).thenReturn(List.of(CategoryCatalog.M_WESTERN_FANTASY));
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(payload(70, 100));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(70, 0, 0));

        service.crawlAllCategories(FanqieConfig.FULL_FETCH_SIZE);

        verify(novelService).saveOrUpdateNovelList(anyList(), eq(CategoryCatalog.M_WESTERN_FANTASY),
                any(), eq(true));
    }

    /** 单页抓取仍按上游 total_num 判完整：首页 30 条不能把 31–100 名当掉榜删掉。 */
    @Test
    void singlePageNeverPrunesEvenWhenItIsTheFirstPage() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(payload(30, 100));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(30, 0, 0));

        service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 30);

        verify(novelService).saveOrUpdateNovelList(anyList(), eq(CategoryCatalog.M_WESTERN_FANTASY),
                any(), eq(false));
    }

    /** 批量只遍历展示中的分类；一个分类空榜只算该分类失败，其余分类仍要跑完。 */
    @Test
    void emptyBoardOnlyFailsItsOwnCategoryInABulkCrawl() throws Exception {
        when(configService.getDisplayedCategories()).thenReturn(List.of(
                CategoryCatalog.M_WESTERN_FANTASY, CategoryCatalog.M_SCI_FI, CategoryCatalog.M_URBAN_HIGH_WU));
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Integer subcategoryId = invocation.getArgument(1);
            if (subcategoryId == 8) {
                return payload(0, 100);
            }
            return rankPayload(2);
        });
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(2, 0, 0));

        List<CategoryCrawlResult> results = service.crawlAllCategories(10);

        List<String> failed = results.stream().filter(CategoryCrawlResult::failed)
                .map(CategoryCrawlResult::code).toList();
        assertEquals(List.of("M_SCI_FI"), failed);
        assertEquals(2 * 2, results.stream().mapToInt(CategoryCrawlResult::fetched).sum());
    }

    /** fetchAll 只抓用户配置展示的分类，而不是整本目录。 */
    @Test
    void bulkCrawlOnlyVisitsDisplayedCategories() throws Exception {
        when(configService.getDisplayedCategories()).thenReturn(List.of(CategoryCatalog.F_ERA));
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(2));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(2, 0, 0));

        List<CategoryCrawlResult> results = service.crawlAllCategories(10);

        assertEquals(1, results.size());
        assertEquals("F_ERA", results.get(0).code());
        assertEquals(0, results.get(0).gender());
        assertEquals(79, results.get(0).subcategoryId());
        verify(apiClient, times(1)).fetchRankUrl(eq(0), eq(79), eq(2), eq(1), eq(10));
        verify(apiClient, never()).fetchRankUrl(eq(1), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /** 拉取频道设成男频：批量拉取跳过女频分类，女频数据既不覆盖也不触发掉榜清理。 */
    @Test
    void bulkCrawlSkipsFemaleCategoriesWhenFetchGenderIsMale() throws Exception {
        when(configService.getDisplayedCategories())
                .thenReturn(List.of(CategoryCatalog.M_SCI_FI, CategoryCatalog.F_ERA));
        when(configService.getFetchGender()).thenReturn(ConfigService.FETCH_MALE);
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(2));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(2, 0, 0));

        List<CategoryCrawlResult> results = service.crawlAllCategories(10);

        assertEquals(1, results.size());
        assertEquals("M_SCI_FI", results.get(0).code());
        // 女频分类一次都没请求
        verify(apiClient, never()).fetchRankUrl(eq(0), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void bulkCrawlSummarizesEveryDisplayedCategoryAndSurvivesPartialFailures() throws Exception {
        List<CategoryCatalog> displayed = List.of(
                CategoryCatalog.M_WESTERN_FANTASY, CategoryCatalog.M_SCI_FI, CategoryCatalog.M_HISTORY_ANCIENT);
        when(configService.getDisplayedCategories()).thenReturn(displayed);
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Integer subcategoryId = invocation.getArgument(1);
            if (subcategoryId == 8) {
                throw new CrawlException("超时");
            }
            return rankPayload(2);
        });
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(2, 0, 0));

        List<CategoryCrawlResult> results = service.crawlAllCategories(10);

        assertEquals(displayed.size(), results.size());
        List<String> failed = results.stream().filter(CategoryCrawlResult::failed)
                .map(CategoryCrawlResult::name).toList();
        assertEquals(List.of("科幻末世"), failed);
        assertEquals(2 * (displayed.size() - 1),
                results.stream().mapToInt(CategoryCrawlResult::fetched).sum());
    }

    @Test
    void mergedListSkipsFailedCategories() throws Exception {
        when(configService.getDisplayedCategories()).thenReturn(List.of(
                CategoryCatalog.M_WESTERN_FANTASY, CategoryCatalog.M_EASTERN_XIANXIA));
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(1));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(1, 0, 0));

        List<NovelInfo> all = service.getAllRankList(10);
        assertEquals(2, all.size());
    }

    /** /api/rank/default 是排查兜底：分类身份来自 yml 默认档，榜单类型仍随 activeRankType。 */
    @Test
    void defaultCrawlResolvesTheConfiguredCategoryAndActiveMold() throws Exception {
        when(configService.getActiveRankType()).thenReturn("new");
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(rankPayload(1));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(1, 0, 0));

        RankListResult result = service.getDefaultRankList();

        assertEquals("西方奇幻", result.novels().get(0).getCategoryName());
        assertEquals("M_WESTERN_FANTASY", result.novels().get(0).getCategoryCode());
        verify(apiClient).fetchRankUrl(eq(1), eq(1141), eq(1), eq(1), eq(30));
        verify(novelService).saveOrUpdateNovelList(anyList(), eq(CategoryCatalog.M_WESTERN_FANTASY),
                eq("new"), anyBoolean());
    }

    @Test
    void wholeListCrawlAllowsPruningStaleRows() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(payload(3, 3));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(3, 0, 0));

        service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 3);

        verify(novelService).saveOrUpdateNovelList(anyList(), eq(CategoryCatalog.M_WESTERN_FANTASY),
                any(), eq(true));
    }

    @Test
    void partialPageNeverPrunesStaleRows() throws Exception {
        when(apiClient.fetchRankUrl(anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(payload(3, 100));
        when(novelService.saveOrUpdateNovelList(anyList(), any(), any(), anyBoolean()))
                .thenReturn(new NovelService.UpsertSummary(3, 0, 0));

        service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 1, 3);
        service.getRankList(CategoryCatalog.M_WESTERN_FANTASY, 2, 3);

        verify(novelService, times(2)).saveOrUpdateNovelList(anyList(), eq(CategoryCatalog.M_WESTERN_FANTASY),
                any(), eq(false));
    }
}
