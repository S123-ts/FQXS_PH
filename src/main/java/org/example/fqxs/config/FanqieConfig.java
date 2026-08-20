// src/main/java/org/example/fqxs/config/FanqieConfig.java
package org.example.fqxs.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "crawler.fanqie")
public class FanqieConfig {

    private String baseUrl = "https://fanqienovel.com";

    // ★ 修改：使用新的API路径
    private String rankApi = "/api/rank/category/list";

    private long timeout = 15000;
    private int maxRetries = 3;
    private DefaultConfig defaultConfig = new DefaultConfig();

    @Data
    public static class DefaultConfig {
        private int rankType = 1;
        private int categoryId = 2;
        private int subcategoryId = 1141;
        private int page = 1;
        private int size = 30;

        // ★ 新增：接口固定参数
        private int appId = 2503;
        private int rankListType = 3;
        private int gender = 1;
        private int rankMold = 2;
        private String rankVersion = "";
    }

    /**
     * ★ 修改：构建新接口的URL
     */
    public String buildRankUrl(Integer rankType, Integer categoryId,
                               Integer subcategoryId, Integer page, Integer size) {
        int offset = (page - 1) * size;
        String url = String.format(
                "%s%s?app_id=%d&rank_list_type=%d&offset=%d&limit=%d&category_id=%d&rank_version=%s&gender=%d&rankMold=%d",
                baseUrl, rankApi,
                defaultConfig.getAppId(),
                defaultConfig.getRankListType(),
                offset,
                size,
                subcategoryId,
                defaultConfig.getRankVersion(),
                defaultConfig.getGender(),
                defaultConfig.getRankMold()
        );
        log.info("构建URL: {}", url);
        return url;
    }

    public String buildDefaultRankUrl() {
        return buildRankUrl(
                defaultConfig.getRankType(),
                defaultConfig.getCategoryId(),
                defaultConfig.getSubcategoryId(),
                defaultConfig.getPage(),
                defaultConfig.getSize()
        );
    }
}