package org.example.fqxs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.model.RankListResult;
import org.example.fqxs.service.ConfigService;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.service.RankService;
import org.example.fqxs.web.GlobalExceptionHandler;
import org.example.fqxs.web.RankWriteInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RankControllerTest {

    private static final Set<String> DEFAULT_DISPLAYED = new LinkedHashSet<>(List.of(
            "M_WESTERN_FANTASY", "M_EASTERN_XIANXIA", "M_SCI_FI", "M_URBAN_HIGH_WU",
            "M_HISTORY_ANCIENT", "M_URBAN_FARMING", "M_TRADITIONAL_XUANHUAN",
            "M_HISTORY_BRAIN", "M_XUANHUAN_BRAIN", "M_GAME_SPORTS"));

    @Mock
    private RankService rankService;

    @Mock
    private NovelService novelService;

    @Mock
    private ConfigService configService;

    private final MockHttpSession session = new MockHttpSession();
    private final ObjectMapper mapper = new ObjectMapper();

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getActiveRankType()).thenReturn("read");
        lenient().when(configService.getDisplayCategoryCodes()).thenReturn(List.copyOf(DEFAULT_DISPLAYED));

        mvc = MockMvcBuilders.standaloneSetup(
                        new RankController(rankService, new FanqieConfig(), novelService, configService, mapper))
                .addInterceptors(new RankWriteInterceptor(mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 抓取会写库，GET 必须被拒：浏览器预取和扫描器不该顺手改写数据。 */
    @Test
    void crawlEndpointsArePostOnly() throws Exception {
        mvc.perform(get("/api/rank").session(session)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/rank/fetchAll").session(session)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/rank/all").session(session)).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(rankService);
    }

    @Test
    void writesRequireTheSessionToken() throws Exception {
        mvc.perform(post("/api/rank/block/123").session(session)).andExpect(status().isUnauthorized());

        verifyNoInteractions(novelService);
    }

    @Test
    void pageCanCrawlAfterFetchingItsToken() throws Exception {
        when(rankService.getRankList(any(), anyInt(), anyInt()))
                .thenReturn(new RankListResult(List.of(), true));

        MvcResult issued = mvc.perform(get("/api/rank/write-token").session(session))
                .andExpect(status().isOk())
                .andReturn();
        String token = mapper.readTree(issued.getResponse().getContentAsString()).get("data").asText();

        mvc.perform(post("/api/rank").header(RankWriteInterceptor.HEADER, token)
                        .param("category", "M_WESTERN_FANTASY").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.dbSaved").value(true))
                .andExpect(jsonPath("$.categoryCode").value("M_WESTERN_FANTASY"))
                .andExpect(jsonPath("$.gender").value(1));
    }

    /** 未知分类 code 一律 400，不吞不抛 500。 */
    @Test
    void crawlWithUnknownCategoryCodeIsRejected() throws Exception {
        MvcResult issued = mvc.perform(get("/api/rank/write-token").session(session)).andReturn();
        String token = mapper.readTree(issued.getResponse().getContentAsString()).get("data").asText();

        mvc.perform(post("/api/rank").header(RankWriteInterceptor.HEADER, token)
                        .param("category", "WESTERN_FANTASY").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数 category 取值不合法"));

        verifyNoInteractions(rankService);
    }

    @Test
    void readEndpointsNeedNoToken() throws Exception {
        when(novelService.findAllBooksFiltered(anyList(), any(), any(), anyBoolean())).thenReturn(List.of());

        mvc.perform(get("/api/rank/allBooks").param("category", "M_WESTERN_FANTASY").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(novelService).findAllBooksFiltered(eq(List.of("M_WESTERN_FANTASY")), any(), any(), eq(false));
    }

    /** fetchAll 的汇总字段在信封顶层（与 data 平级），前端 data.js 显式依赖这个位置读
        failedCategories/dbNotSavedCategories；挪进 data 里前端会静默读空。 */
    @Test
    void fetchAllPutsSummaryFieldsAtEnvelopeTopLevel() throws Exception {
        when(rankService.crawlAllCategories(100)).thenReturn(List.of(
                new CategoryCrawlResult("M_SCI_FI", "科幻末世", 1, 8, 100, true, null),
                new CategoryCrawlResult("F_ERA", "年代", 0, 79, 0, false, "上游风控"),
                new CategoryCrawlResult("M_SCI_FI", "科幻末世", 1, 8, 50, false, null)));

        MvcResult issued = mvc.perform(get("/api/rank/write-token").session(session))
                .andExpect(status().isOk())
                .andReturn();
        String token = mapper.readTree(issued.getResponse().getContentAsString()).get("data").asText();

        // fetchAll 返回 Callable（异步执行），MockMvc 要二段派发才能拿到响应体
        MvcResult async = mvc.perform(post("/api/rank/fetchAll")
                        .header(RankWriteInterceptor.HEADER, token).session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.totalBooks").value(150))
                .andExpect(jsonPath("$.failedCategories.length()").value(1))
                .andExpect(jsonPath("$.failedCategories[0]").value("年代(上游风控)"))
                .andExpect(jsonPath("$.dbNotSavedCategories.length()").value(1))
                .andExpect(jsonPath("$.dbNotSavedCategories[0]").value("科幻末世"))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    /** 展示分类清空后 fetchAll 空跑：200 + 空汇总，而不是 500 或越界。 */
    @Test
    void fetchAllWithNoDisplayedCategoriesReturnsEmptySummary() throws Exception {
        when(rankService.crawlAllCategories(100)).thenReturn(List.of());

        MvcResult issued = mvc.perform(get("/api/rank/write-token").session(session)).andReturn();
        String token = mapper.readTree(issued.getResponse().getContentAsString()).get("data").asText();

        MvcResult async = mvc.perform(post("/api/rank/fetchAll")
                        .header(RankWriteInterceptor.HEADER, token).session(session))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBooks").value(0))
                .andExpect(jsonPath("$.failedCategories.length()").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** allBooks 的 code 列表也要过白名单。 */
    @Test
    void allBooksRejectsUnknownCategoryCodes() throws Exception {
        mvc.perform(get("/api/rank/allBooks").param("category", "M_SCI_FI,NOPE").session(session))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(novelService);
    }

    /** meta 是页面唯一引导接口：37 项全目录、男频块在前、displayed 标记与当前榜单类型一次拿全。 */
    @Test
    void metaReturnsTheWholeCatalogWithDisplayedFlags() throws Exception {
        mvc.perform(get("/api/rank/meta").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.activeRankType").value("read"))
                .andExpect(jsonPath("$.data.rankTypes.length()").value(2))
                .andExpect(jsonPath("$.data.rankTypes[0].code").value("read"))
                .andExpect(jsonPath("$.data.rankTypes[0].name").value("阅读榜"))
                .andExpect(jsonPath("$.data.rankTypes[0].rankMold").value(2))
                .andExpect(jsonPath("$.data.rankTypes[1].code").value("new"))
                .andExpect(jsonPath("$.data.rankTypes[1].rankMold").value(1))
                .andExpect(jsonPath("$.data.categories.length()").value(37))
                // 男频块在前女频块在后，顺序按目录
                .andExpect(jsonPath("$.data.categories[0].code").value("M_WESTERN_FANTASY"))
                .andExpect(jsonPath("$.data.categories[0].displayed").value(true))
                .andExpect(jsonPath("$.data.categories[18].code").value("M_MALE_DERIVED"))
                .andExpect(jsonPath("$.data.categories[19].code").value("F_ANCIENT_ROMANCE"))
                .andExpect(jsonPath("$.data.categories[19].gender").value(0))
                .andExpect(jsonPath("$.data.categories[19].displayed").value(false))
                .andExpect(jsonPath("$.data.categories[36].code").value("F_REPUBLIC_ROMANCE"))
                .andExpect(jsonPath("$.data.categories[36].subcategoryId").value(1017));
    }

    @Test
    void categoriesEndpointMatchesMetaCatalog() throws Exception {
        mvc.perform(get("/api/rank/categories").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(37))
                .andExpect(jsonPath("$.data[0].code").value("M_WESTERN_FANTASY"))
                .andExpect(jsonPath("$.data[0].displayed").value(true))
                .andExpect(jsonPath("$.data[20].code").value("F_SCI_FI"))
                .andExpect(jsonPath("$.data[20].displayed").value(false));
    }

    /** /api/rank/config 是写操作：被 X-Rank-Token 拦截器覆盖（/api/rank/** 通配）。 */
    @Test
    void configUpdateRequiresTheWriteToken() throws Exception {
        mvc.perform(post("/api/rank/config").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rankType\":\"new\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(configService);
    }

    @Test
    void configUpdateAppliesAndReturnsTheMetaShape() throws Exception {
        MvcResult issued = mvc.perform(get("/api/rank/write-token").session(session)).andReturn();
        String token = mapper.readTree(issued.getResponse().getContentAsString()).get("data").asText();

        mvc.perform(post("/api/rank/config").header(RankWriteInterceptor.HEADER, token).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rankType\":\"new\",\"displayCategories\":[\"M_SCI_FI\",\"F_ERA\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rankTypes.length()").value(2))
                .andExpect(jsonPath("$.data.categories.length()").value(37))
                .andExpect(jsonPath("$.data.categories[2].code").value("M_SCI_FI"));

        verify(configService).updateConfig("new", List.of("M_SCI_FI", "F_ERA"));
    }

    /** rankType 取值域由 @Pattern 钉住，非法值 400 且不触碰配置服务。 */
    @Test
    void configUpdateRejectsUnknownRankType() throws Exception {
        MvcResult issued = mvc.perform(get("/api/rank/write-token").session(session)).andReturn();
        String token = mapper.readTree(issued.getResponse().getContentAsString()).get("data").asText();

        mvc.perform(post("/api/rank/config").header(RankWriteInterceptor.HEADER, token).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rankType\":\"hot\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("参数 rankType 只支持 read 或 new"));

        verify(configService, never()).updateConfig(any(), any());
    }

    @Test
    void configGetAddsActiveSettingsToTheCrawlerConfig() throws Exception {
        mvc.perform(get("/api/rank/config").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rankType").value("read"))
                .andExpect(jsonPath("$.data.displayCategories[0]").value("M_WESTERN_FANTASY"))
                .andExpect(jsonPath("$.data.baseUrl").value("https://fanqienovel.com"));
    }
}
