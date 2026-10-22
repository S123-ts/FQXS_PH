package org.example.fqxs.service;

import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.model.CategoryCrawlResult;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.model.RankListResult;

import java.util.List;

public interface RankService {

    /** 抓取单个分类（榜单类型取自 activeRankType）；失败抛 CrawlException，与“抓取成功但列表为空”区分开。 */
    RankListResult getRankList(CategoryCatalog category, int page, int size);

    /** 用 crawler.fanqie.default 的子分类兜底配置抓一个榜（排查用）。 */
    RankListResult getDefaultRankList();

    /** 抓全部展示中的分类并合并成一份列表。 */
    List<NovelInfo> getAllRankList(int size);

    /** 抓全部展示中的分类并返回逐分类汇总；全部失败时抛 CrawlException。 */
    List<CategoryCrawlResult> crawlAllCategories(int size);
}
