package org.example.fqxs.config;

import org.example.fqxs.constant.CategoryCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FanqieConfigTest {

    private final FanqieConfig config = new FanqieConfig();

    @Test
    void urlCarriesGenderSubcategoryAndRankMold() {
        String url = config.buildRankUrl(1, 1141, 2, 1, 30);

        assertThat(url)
                .startsWith("https://fanqienovel.com/api/rank/category/list?")
                .contains("app_id=2503", "rank_list_type=3", "offset=0", "limit=30",
                        "category_id=1141", "gender=1", "rankMold=2");
    }

    /** 女频新书榜：gender 与 rankMold 必须原样进入 URL，这是四种榜的寻址开关。 */
    @Test
    void femaleNewBookRankUsesItsOwnAddressing() {
        assertThat(config.buildRankUrl(0, 8, 1, 1, 30))
                .contains("category_id=8", "gender=0", "rankMold=1");
    }

    @Test
    void pagingIsTranslatedIntoOffset() {
        assertThat(config.buildRankUrl(1, 8, 2, 3, 50)).contains("offset=100", "limit=50");
    }

    @Test
    void bogusPageAndSizeAreClampedInsteadOfProducingNegativeOffsets() {
        assertThat(config.buildRankUrl(0, 8, 1, 0, 0)).contains("offset=0", "limit=1");
        assertThat(config.buildRankUrl(0, 8, 1, -5, 99999)).contains("offset=0", "limit=100");
        assertThat(config.buildRankUrl(0, 8, 1, null, null)).contains("offset=0", "limit=1");
    }

    /** DefaultConfig 仅作排查兜底；运行时榜单类型由 app_config 的 rank_type 决定。 */
    @Test
    void defaultConfigPinsTheTroubleshootingDefaults() {
        FanqieConfig.DefaultConfig defaults = config.getDefaultConfig();
        assertThat(defaults.getSubcategoryId()).isEqualTo(1141);
        assertThat(defaults.getGender()).isEqualTo(1);
        assertThat(defaults.getRankMold()).isEqualTo(2);
        assertThat(defaults.getRankListType()).isEqualTo(3);
        assertThat(defaults.getAppId()).isEqualTo(2503);
    }

    @Test
    void wholeListCrawlFetchesEveryRankNotThePageSize() {
        assertThat(FanqieConfig.wholeListSize(null)).isEqualTo(FanqieConfig.MAX_PAGE_SIZE);
        assertThat(FanqieConfig.wholeListSize(20)).isEqualTo(20);
        assertThat(FanqieConfig.wholeListSize(99999)).isEqualTo(FanqieConfig.MAX_PAGE_SIZE);
    }

    @Test
    void catalogResolvesEveryEntryFromItsCodeAndGenderPair() {
        assertThat(CategoryCatalog.values()).hasSize(37);
        assertThat(CategoryCatalog.fromCode("M_SCI_FI"))
                .isEqualTo(CategoryCatalog.M_SCI_FI);
        assertThat(CategoryCatalog.fromCode("sci_fi")).isNull();
        // 男女频共用 subcategoryId，不带 gender 解析不了
        assertThat(CategoryCatalog.fromSubcategoryId(1, 8)).isEqualTo(CategoryCatalog.M_SCI_FI);
        assertThat(CategoryCatalog.fromSubcategoryId(0, 8)).isEqualTo(CategoryCatalog.F_SCI_FI);
        assertThat(CategoryCatalog.fromSubcategoryId(1, 746)).isEqualTo(CategoryCatalog.M_GAME_SPORTS);
        assertThat(CategoryCatalog.fromSubcategoryId(0, 746)).isEqualTo(CategoryCatalog.F_GAME_SPORTS);
    }
}
