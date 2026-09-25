package org.example.fqxs.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.model.ConfigUpdateRequest;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.model.RankListResult;
import org.example.fqxs.service.ConfigService;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.service.RankService;
import org.example.fqxs.web.ApiResult;
import org.example.fqxs.web.RankWriteInterceptor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Slf4j
@RestController
@RequestMapping(value = "/api/rank", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;
    private final FanqieConfig fanqieConfig;
    private final NovelService novelService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /** 页面唯一引导接口：榜单类型、当前生效配置与全目录（含 displayed 标记）一次拿全。 */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return ApiResult.data("success", buildMetaView());
    }

    /**
     * 写配置（X-Rank-Token 拦截器覆盖 /api/rank/**，POST 必须带会话令牌）。
     * 两字段都可选；落库成功后返回与新配置生效后的 meta 相同形状的全量视图。
     */
    @PostMapping("/config")
    public Map<String, Object> updateConfig(@Valid @RequestBody ConfigUpdateRequest request) {
        configService.updateConfig(request.getRankType(), request.getDisplayCategories());
        return ApiResult.data("配置已更新", buildMetaView());
    }

    /** 保留排查用：crawler.fanqie 原始配置 + 当前生效的 rankType 与 displayCategories。 */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> data = objectMapper.convertValue(
                fanqieConfig, new TypeReference<LinkedHashMap<String, Object>>() {
                });
        data.put("rankType", configService.getActiveRankType());
        data.put("displayCategories", configService.getDisplayCategoryCodes());
        return ApiResult.data("success", data);
    }

    /**
     * 抓取会写库（upsert + 清理掉榜行），因此只能是 POST；
     * 页面需先 GET /write-token 领取会话令牌并带在 X-Rank-Token 上。
     */
    @PostMapping("/default")
    public Map<String, Object> crawlDefaultRankList() {
        RankListResult result = rankService.getDefaultRankList();
        Map<String, Object> body = ApiResult.list(result.novels());
        body.put("dbSaved", result.dbSaved());
        body.put("config", fanqieConfig.getDefaultConfig());
        return body;
    }

    /**
     * category 语义是全局唯一分类 code（M_/F_）；榜单类型（阅读/新书）由 activeRankType 决定。
     * 单分类抓取固定抓满整榜，翻页在前端切片。
     */
    @PostMapping
    public Map<String, Object> crawlRankList(
            @RequestParam String category,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "30") Integer size) {

        CategoryCatalog target = CategoryCatalog.fromCode(category);
        if (target == null) {
            throw new IllegalArgumentException("参数 category 取值不合法");
        }
        int safePage = FanqieConfig.clampPage(page);
        int safeSize = FanqieConfig.clampSize(size);
        log.info("请求: category={}, gender={}, subcategoryId={}, page={}, size={}",
                target.getCode(), target.getGender(), target.getSubcategoryId(), safePage, safeSize);

        RankListResult result = rankService.getRankList(target, safePage, safeSize);
        Map<String, Object> body = ApiResult.list(result.novels());
        body.put("category", target.getName());
        body.put("categoryCode", target.getCode());
        body.put("gender", target.getGender());
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("dbSaved", result.dbSaved());
        return body;
    }

    @PostMapping("/all")
    public Map<String, Object> crawlAllRankList(@RequestParam(defaultValue = "30") Integer size) {
        List<NovelInfo> novels = rankService.getAllRankList(FanqieConfig.clampSize(size));
        return ApiResult.list(novels);
    }

    /** 页面（含非浏览器脚本）先领一次会话令牌；GET 本身不改任何数据。 */
    @GetMapping("/write-token")
    public Map<String, Object> writeToken(HttpSession session) {
        return ApiResult.data("success", RankWriteInterceptor.issueToken(session));
    }

    /**
     * 逐分类抓取耗时以十秒计，用 Callable 交给 crawlTaskExecutor 执行，
     * 避免长时间占用 Tomcat 请求线程；并发由 RankService 的爬取锁拦截。
     * size 不传即抓满整个榜单，页面“每页显示”与该按钮无关。
     * 只抓展示中的分类：用户配置显示哪些就抓哪些。
     */
    @PostMapping("/fetchAll")
    public Callable<Map<String, Object>> fetchAllCategories(
            @RequestParam(required = false) Integer size) {
        int safeSize = FanqieConfig.wholeListSize(size);
        log.info("一键获取展示分类，每类 {} 本", safeSize);
        return () -> {
            long start = System.currentTimeMillis();
            List<CategoryCrawlResult> results = rankService.crawlAllCategories(safeSize);

            int totalBooks = results.stream().mapToInt(CategoryCrawlResult::fetched).sum();
            List<String> failed = results.stream()
                    .filter(CategoryCrawlResult::failed)
                    .map(result -> result.name() + "(" + result.error() + ")")
                    .toList();
            // 抓到了但一行都没落库的分类：数据库停着时也必须在这里露出来
            List<String> notSaved = results.stream()
                    .filter(result -> !result.failed() && !result.dbSaved())
                    .map(CategoryCrawlResult::name)
                    .toList();

            StringBuilder message = new StringBuilder("所有分类数据获取完成，共 ")
                    .append(totalBooks).append(" 本小说");
            if (!failed.isEmpty()) {
                message.append("，").append(failed.size()).append(" 个分类失败");
            }
            if (!notSaved.isEmpty()) {
                message.append("，").append(notSaved.size())
                        .append(" 个分类未落库（数据库不可用）");
            }

            Map<String, Object> body = new LinkedHashMap<>(ApiResult.ok());
            body.put("message", message.toString());
            body.put("data", results);
            body.put("totalBooks", totalBooks);
            body.put("failedCategories", failed);
            body.put("dbNotSavedCategories", notSaved);
            body.put("timeMillis", System.currentTimeMillis() - start);
            log.info("一键获取完成: {} 本, 失败分类 {}, 未落库分类 {}, 耗时 {} ms", totalBooks, failed, notSaved,
                    System.currentTimeMillis() - start);
            return body;
        };
    }

    /** 全目录（含 displayed 标记），供旧调用方兼容；新页面引导走 GET /meta。 */
    @GetMapping("/categories")
    public Map<String, Object> getCategories() {
        return ApiResult.list(categoryView());
    }

    /** “全部”视图：category 传逗号分隔的分类 code，空参数=全库（排查用）。 */
    @GetMapping("/allBooks")
    public Map<String, Object> getAllBooksFromDb(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String wordRange,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean blockedOnly) {

        List<String> codes = splitCategories(category);
        for (String code : codes) {
            if (CategoryCatalog.fromCode(code) == null) {
                throw new IllegalArgumentException("参数 category 取值不合法");
            }
        }
        List<Novel> novels = novelService.findAllBooksFiltered(codes, wordRange, status, blockedOnly);
        return ApiResult.list(novels);
    }

    @PostMapping("/block/{bookId}")
    public Map<String, Object> blockBook(@PathVariable String bookId) {
        novelService.setBlocked(bookId, true);
        return ApiResult.ok("拉黑成功");
    }

    /** 拉黑是单向开关就没有回头路了，这里给回滚入口。 */
    @PostMapping("/unblock/{bookId}")
    public Map<String, Object> unblockBook(@PathVariable String bookId) {
        novelService.setBlocked(bookId, false);
        return ApiResult.ok("已恢复");
    }

    private Map<String, Object> buildMetaView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("rankTypes", rankTypes());
        view.put("activeRankType", configService.getActiveRankType());
        view.put("categories", categoryView());
        return view;
    }

    private List<Map<String, Object>> rankTypes() {
        List<Map<String, Object>> rankTypes = new ArrayList<>();
        rankTypes.add(rankType("read", "阅读榜", 2));
        rankTypes.add(rankType("new", "新书榜", 1));
        return rankTypes;
    }

    private Map<String, Object> rankType(String code, String name, int rankMold) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", code);
        entry.put("name", name);
        entry.put("rankMold", rankMold);
        return entry;
    }

    /** 全 37 项，男频块在前女频块在后，顺序即目录声明顺序。 */
    private List<Map<String, Object>> categoryView() {
        Set<String> displayed = new LinkedHashSet<>(configService.getDisplayCategoryCodes());
        List<Map<String, Object>> categories = new ArrayList<>();
        for (CategoryCatalog item : CategoryCatalog.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", item.getCode());
            entry.put("name", item.getName());
            entry.put("gender", item.getGender());
            entry.put("subcategoryId", item.getSubcategoryId());
            entry.put("displayed", displayed.contains(item.getCode()));
            categories.add(entry);
        }
        return categories;
    }

    private List<String> splitCategories(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        return Arrays.stream(category.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
