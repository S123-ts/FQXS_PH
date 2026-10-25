package org.example.fqxs.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.FanqieApiClient;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.model.RankListResult;
import org.example.fqxs.service.ConfigService;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.service.RankService;
import org.example.fqxs.util.DateUtil;
import org.example.fqxs.util.NovelTextParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {

    /** 全量爬取各分类之间留一点间隔，避免连续打满上游接口。 */
    private static final long CATEGORY_INTERVAL_MS = 100L;

    /** 单分类抓取最多等这么久让位给全量任务，超过就报错而不是排队挂住线程。 */
    private static final long CRAWL_LOCK_WAIT_MS = 60_000L;

    private final FanqieApiClient apiClient;
    private final FanqieConfig fanqieConfig;
    private final NovelService novelService;
    private final ConfigService configService;

    private final ReentrantLock fullCrawlLock = new ReentrantLock();

    @Override
    public RankListResult getRankList(CategoryCatalog category, int page, int size) {
        Outcome outcome = crawlCategory(category, page, size);
        if (outcome.error() != null) {
            throw new CrawlException("分类[" + category.getName() + "]抓取失败: " + outcome.error());
        }
        return new RankListResult(outcome.novels(), outcome.dbSaved());
    }

    @Override
    public RankListResult getDefaultRankList() {
        FanqieConfig.DefaultConfig defaults = fanqieConfig.getDefaultConfig();
        CategoryCatalog category = CategoryCatalog.fromSubcategoryId(
                defaults.getGender(), defaults.getSubcategoryId());
        if (category == null) {
            // 兜底配置指向不存在的 (gender, subcategoryId) 时宁可报错，也不把脏数据写进库里
            throw new CrawlException("默认榜单配置无法解析: gender=" + defaults.getGender()
                    + ", subcategoryId=" + defaults.getSubcategoryId());
        }
        // 抓取即写库，榜单类型必须在发起请求前定死并一路带到 persist，中途切榜不会串分区
        String rankType = configService.getActiveRankType();
        log.info("使用默认配置爬取: subcategoryId={}, category={}, rankType={}",
                defaults.getSubcategoryId(), category.getName(), rankType);

        if (!acquireCrawlLock()) {
            throw new CrawlException("上游抓取正被其他任务占用，请稍后重试");
        }
        try {
            int safeSize = FanqieConfig.clampSize(defaults.getSize());
            JsonNode root = apiClient.fetchRankUrl(category.getGender(), category.getSubcategoryId(),
                    moldOf(rankType), defaults.getPage(), safeSize);
            List<NovelInfo> novels = toNovelInfos(category, root, defaults.getPage(), safeSize);
            boolean complete = coversWholeList(root, defaults.getPage(), novels.size(), safeSize, false);
            return new RankListResult(novels, persist(novels, category, rankType, complete));
        } finally {
            fullCrawlLock.unlock();
        }
    }

    @Override
    public List<NovelInfo> getAllRankList(int size) {
        List<NovelInfo> all = new ArrayList<>();
        for (Crawled crawled : crawlEveryCategory(size, false)) {
            if (crawled.outcome().error() == null) {
                all.addAll(crawled.outcome().novels());
            }
        }
        return all;
    }

    @Override
    public List<CategoryCrawlResult> crawlAllCategories(int size) {
        List<CategoryCrawlResult> results = new ArrayList<>();
        for (Crawled crawled : crawlEveryCategory(size, true)) {
            Outcome outcome = crawled.outcome();
            CategoryCatalog category = crawled.category();
            results.add(new CategoryCrawlResult(
                    category.getCode(),
                    category.getName(),
                    category.getGender(),
                    category.getSubcategoryId(),
                    outcome.novels().size(),
                    outcome.dbSaved(),
                    outcome.error()));
        }
        return results;
    }

    /** 只遍历展示中的分类：用户配置显示哪些就抓哪些，“一键获取”抓的是页面看得到的榜。 */
    private List<Crawled> crawlEveryCategory(int size, boolean fullFetch) {
        List<CategoryCatalog> displayed = configService.getDisplayedCategories();
        // 拉取频道限制（fetch_gender）：自用只拉男频时，女频分类既不覆盖也不触发掉榜清理
        int genderFilter = ConfigService.genderCodeOf(configService.getFetchGender());
        List<CategoryCatalog> targets = genderFilter < 0
                ? displayed
                : displayed.stream().filter(category -> category.getGender() == genderFilter).toList();
        if (targets.size() < displayed.size()) {
            log.info("拉取频道={}，跳过 {} 个不匹配分类，本次拉取 {} 个",
                    configService.getFetchGender(), displayed.size() - targets.size(), targets.size());
        }
        if (!fullCrawlLock.tryLock()) {
            throw new CrawlException("已有全量爬取任务正在执行，请稍后再试");
        }
        try {
            // 整批锁定同一个榜单类型：批量进行中用户切榜，不让同批前后分类来自不同榜单
            String rankType = configService.getActiveRankType();
            List<Crawled> crawled = new ArrayList<>();
            for (CategoryCatalog category : targets) {
                crawled.add(new Crawled(category, crawlCategory(category, 1, size, rankType, fullFetch)));
                rest();
            }
            int failed = 0;
            for (Crawled item : crawled) {
                if (item.outcome().error() != null) {
                    failed++;
                }
            }
            if (!crawled.isEmpty() && failed == crawled.size()) {
                throw new CrawlException("所有分类均抓取失败，最后错误: "
                        + crawled.get(crawled.size() - 1).outcome().error());
            }
            log.info("全量爬取完成: 成功 {}/{} 个分类", crawled.size() - failed, crawled.size());
            return crawled;
        } finally {
            fullCrawlLock.unlock();
        }
    }

    /** 单分类抓取：网络/写库异常都记进 Outcome，批量任务因此能跑完剩余分类。 */
    private Outcome crawlCategory(CategoryCatalog category, int page, int size) {
        // 单分类抓取每次读当前生效的榜单类型；批量路径由 crawlEveryCategory 锁定后传入
        return crawlCategory(category, page, size, configService.getActiveRankType(), false);
    }

    private Outcome crawlCategory(CategoryCatalog category, int page, int size, String rankType,
                                  boolean fullFetch) {
        if (!acquireCrawlLock()) {
            return new Outcome(List.of(), "上游抓取正被其他任务占用，请稍后重试", false);
        }
        try {
            int safePage = FanqieConfig.clampPage(page);
            int safeSize = FanqieConfig.clampSize(size);
            JsonNode root = apiClient.fetchRankUrl(category.getGender(), category.getSubcategoryId(),
                    moldOf(rankType), safePage, safeSize);
            List<NovelInfo> novels = toNovelInfos(category, root, safePage, safeSize);
            boolean complete = coversWholeList(root, safePage, novels.size(), safeSize, fullFetch);
            boolean saved = persist(novels, category, rankType, complete);
            return new Outcome(novels, null, saved);
        } catch (CrawlException e) {
            log.error("分类[{}]抓取失败: {}", category.getName(), e.getMessage());
            return new Outcome(List.of(), e.getMessage(), false);
        } finally {
            fullCrawlLock.unlock();
        }
    }

    /** 单分类抓取也要排队：与全量任务并发时，两条事务会交替改写同一分类。 */
    private boolean acquireCrawlLock() {
        try {
            return fullCrawlLock.tryLock(CRAWL_LOCK_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<NovelInfo> toNovelInfos(CategoryCatalog category, JsonNode root, int page, int size) {
        JsonNode data = root.path("data");
        JsonNode listNode = findListNode(root);
        long total = NovelTextParser.firstLong(data, "total_num", "totalNum");
        if (listNode == null || listNode.isEmpty()) {
            // 已知每个分类恒定 100 条，抓到 0 条不是“榜是空的”，而是上游换了字段或被风控
            log.warn("分类[{}]响应异常: 列表字段缺失或为空，实际字段 {}", category.getName(),
                    NovelTextParser.fieldNames(data.isObject() ? data : root));
            throw new CrawlException("分类[" + category.getName() + "]返回 0 条（total_num=" + total
                    + "），疑似上游换字段或被风控");
        }

        List<NovelInfo> novels = new ArrayList<>();
        int rank = (page - 1) * size + 1;
        for (JsonNode book : listNode) {
            if (novels.isEmpty()) {
                // 上游字段命名混用（camel / snake），出问题时先看这行日志
                log.info("分类[{}]首条记录字段: {}", category.getName(), NovelTextParser.fieldNames(book));
            }
            NovelInfo info = parseNovel(book, String.valueOf(rank++));
            info.setCategoryName(category.getName());
            info.setCategoryCode(category.getCode());
            info.setGender(category.getGender());
            novels.add(info);
        }
        log.info("分类[{}]获取到 {} 条数据", category.getName(), novels.size());
        return novels;
    }

    /** 上游列表字段有过三种写法，按顺序探测。 */
    private JsonNode findListNode(JsonNode root) {
        JsonNode data = root.path("data");
        for (String field : new String[]{"book_list", "list"}) {
            JsonNode candidate = data.path(field);
            if (candidate.isArray()) {
                return candidate;
            }
        }
        JsonNode topLevel = root.path("list");
        return topLevel.isArray() ? topLevel : null;
    }

    /** 写库失败不阻断本次展示（数据库可能没启动），但结果会通过 dbSaved 透出。 */
    private boolean persist(List<NovelInfo> novels, CategoryCatalog category, String rankType,
                            boolean pruneStale) {
        try {
            NovelService.UpsertSummary summary =
                    novelService.saveOrUpdateNovelList(novels, category, rankType, pruneStale);
            log.info("分类[{}]落库完成({}): 新增 {}, 更新 {}, 跳过 {}", category.getName(),
                    rankType, summary.inserted(), summary.updated(), summary.skipped());
            return true;
        } catch (RuntimeException e) {
            log.warn("分类[{}]写库失败（数据库可能未启动），本次仅返回抓取结果: {}", category.getName(), e.getMessage());
            return false;
        }
    }

    /** 榜单类型 → 上游 rankMold 参数（read→2，new→1）；脏值退回阅读榜，与 ConfigService 同规。 */
    private int moldOf(String rankType) {
        return ConfigService.RANK_MOLDS.getOrDefault(rankType,
                ConfigService.RANK_MOLDS.get(ConfigService.RANK_TYPE_READ));
    }

    /**
     * 本次响应是否覆盖了整个榜单。只有首页且拿满 total_num 才允许清理掉榜数据，
     * 否则“每页显示 30”的一页会把 31~100 名当成掉榜数据删掉。
     * 四种 (gender, rankMold) 组合的 total_num 恒为 100，该判定对男女频/新旧榜通用。
     */
    private boolean coversWholeList(JsonNode root, int page, int fetched, int requestedSize,
                                    boolean fullFetch) {
        if (page != 1 || fetched <= 0) {
            return false;
        }
        long total = NovelTextParser.firstLong(root.path("data"), "total_num", "totalNum");
        if (total <= 0) {
            return false;
        }
        // 全量抓取按「本次要抓多少」判完整：70 本就是用户定义的整榜，掉榜行要清掉；
        // 单页抓取仍按上游 total_num（100）判，避免一页 30 条把 31–100 名当掉榜删掉
        long target = fullFetch ? Math.min(total, requestedSize) : total;
        return fetched >= target;
    }

    private void rest() {
        try {
            Thread.sleep(CATEGORY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("全量爬取的分类间隔等待被中断，剩余分类将连续请求");
        }
    }

    private NovelInfo parseNovel(JsonNode book, String rank) {
        return NovelInfo.builder()
                .rank(rank)
                .bookId(NovelTextParser.firstText(book, "bookId", "book_id"))
                .title(NovelTextParser.firstText(book, "bookName", "book_name", "title"))
                .author(NovelTextParser.firstText(book, "author", "authorName", "author_name"))
                .description(NovelTextParser.firstText(book, "abstract", "description", "book_abstract"))
                .status(NovelTextParser.parseStatus(book))
                .readCount(NovelTextParser.formatCount(book, "read_count", "readCount"))
                .readCountRaw(NovelTextParser.firstLong(book, "read_count", "readCount"))
                .lastChapter(NovelTextParser.firstText(book, "lastChapterTitle", "last_chapter_title", "lastChapter"))
                .updateTime(parseTimestamp(book))
                .thumbUri(NovelTextParser.firstText(book, "thumbUri", "thumb_uri", "cover"))
                .wordCount(NovelTextParser.formatCount(book, "wordNumber", "word_number", "wordCount", "word_count"))
                .build();
    }

    private String parseTimestamp(JsonNode book) {
        String timeStr = NovelTextParser.firstText(book,
                "lastChapterUpdateTime", "last_chapter_update_time", "updateTime", "update_time", "lastUpdateTime");
        if (timeStr.isEmpty()) {
            return "";
        }
        try {
            return DateUtil.formatEpoch(Long.parseLong(timeStr.trim()));
        } catch (NumberFormatException e) {
            return timeStr;
        }
    }

    private record Outcome(List<NovelInfo> novels, String error, boolean dbSaved) {
    }

    private record Crawled(CategoryCatalog category, Outcome outcome) {
    }
}
