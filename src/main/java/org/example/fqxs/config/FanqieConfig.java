package org.example.fqxs.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "crawler.fanqie")
public class FanqieConfig {

    /** 单页上限：上游按 100 返回，超过没有意义且会放大内存与写库量。 */
    public static final int MAX_PAGE_SIZE = 100;

    private String baseUrl = "https://fanqienovel.com";

    private String rankApi = "/api/rank/category/list";

    private long timeout = 15000;
    private int maxRetries = 3;
    private DefaultConfig defaultConfig = new DefaultConfig();

    @Data
    public static class DefaultConfig {
        /** 接口的 category_id 取的就是这里的子分类 ID（榜单叶子分类）。 */
        private int subcategoryId = 1141;
        private int page = 1;
        private int size = 30;

        private int appId = 2503;
        private int rankListType = 3;
        /** 1=男频 0=女频；与 rankMold、category_id 共同决定榜单（实测影响寻址）。 */
        private int gender = 1;
        /** 2=阅读榜 1=新书榜；仅作排查兜底，运行时以 app_config 的 rank_type 为准。 */
        private int rankMold = 2;
        private String rankVersion = "";
    }

    public static int clampSize(Integer size) {
        if (size == null || size < 1) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public static int clampPage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /** 全量抓取每分类的条数：上游 total_num 恒为 100，抓满才有资格触发掉榜清理。 */
    public static int wholeListSize(Integer requested) {
        return requested == null ? MAX_PAGE_SIZE : clampSize(requested);
    }

    /**
     * 排行榜接口寻址三元组（上游实测）：gender 决定男/女频（1=男 0=女），
     * rankMold 决定榜单类型（2=阅读榜 1=新书榜），category_id 是分类叶子 ID；
     * 三者任一不同就是不同的榜。rank_list_type 与 app_id 恒定不参与切换。
     */
    public String buildRankUrl(int gender, int subcategoryId, int rankMold, Integer page, Integer size) {
        int safePage = clampPage(page);
        int safeSize = clampSize(size);
        int offset = (safePage - 1) * safeSize;

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + rankApi)
                .queryParam("app_id", defaultConfig.getAppId())
                .queryParam("rank_list_type", defaultConfig.getRankListType())
                .queryParam("offset", offset)
                .queryParam("limit", safeSize)
                .queryParam("category_id", subcategoryId)
                .queryParam("rank_version", defaultConfig.getRankVersion())
                .queryParam("gender", gender)
                .queryParam("rankMold", rankMold)
                .build()
                .encode()
                .toUriString();

        log.debug("构建URL: {}", url);
        return url;
    }
}
