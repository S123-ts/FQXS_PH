package org.example.fqxs.service;

import org.example.fqxs.constant.CategoryCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置的落库语义（真内存库）：播种只补缺失键、往返读写、目录校验。
 * 缓存行为在 ConfigServiceCacheTest 用受控仓库单测。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fqxs-config-test;DB_CLOSE_DELAY=-1",
        "spring.test.database.replace=none",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-h2.sql"
})
@Import(ConfigService.class)
class ConfigServiceTest {

    @Autowired
    private ConfigService configService;

    @Autowired
    private org.example.fqxs.repository.AppConfigRepository appConfigRepository;

    @Test
    void seedsDefaultsOnlyWhenKeysAreMissing() {
        configService.seedDefaults();
        configService.seedDefaults();

        assertThat(appConfigRepository.count()).isEqualTo(2);
        assertThat(configService.getActiveRankType()).isEqualTo("read");
        assertThat(configService.getDisplayCategoryCodes())
                .containsExactly(ConfigService.DEFAULT_DISPLAY_CATEGORIES.split(","));
    }

    @Test
    void rankTypeAndDisplayCategoriesRoundTrip() {
        configService.seedDefaults();
        configService.updateConfig("new", List.of("F_ERA", "M_SCI_FI"));

        assertThat(configService.getActiveRankType()).isEqualTo("new");
        assertThat(configService.getActiveRankMold()).isEqualTo(1);
        assertThat(configService.getDisplayCategoryCodes()).containsExactly("F_ERA", "M_SCI_FI");
        // 展示分类输出按目录顺序
        assertThat(configService.getDisplayedCategories())
                .containsExactly(CategoryCatalog.M_SCI_FI, CategoryCatalog.F_ERA);

        configService.updateConfig("read", null);
        assertThat(configService.getActiveRankType()).isEqualTo("read");
        assertThat(configService.getActiveRankMold()).isEqualTo(2);
        // null 表示不改，displayCategories 保持原值
        assertThat(configService.getDisplayCategoryCodes()).containsExactly("F_ERA", "M_SCI_FI");
    }

    /** 空数组=清空展示分类，是合法状态；fetchAll 因此抓 0 个分类而不是报错。 */
    @Test
    void emptyListClearsDisplayCategories() {
        configService.seedDefaults();
        configService.updateConfig(null, List.of());

        assertThat(configService.getDisplayCategoryCodes()).isEmpty();
        assertThat(configService.getDisplayedCategories()).isEmpty();
    }

    @Test
    void unknownCodesAreRejected() {
        configService.seedDefaults();

        assertThatThrownBy(() -> configService.updateConfig(null, List.of("M_SCI_FI", "M_NOPE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("M_NOPE");
        assertThatThrownBy(() -> configService.updateConfig(null, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        // 校验失败不能留半截写入
        assertThat(configService.getDisplayCategoryCodes())
                .containsExactly(ConfigService.DEFAULT_DISPLAY_CATEGORIES.split(","));
    }

    @Test
    void duplicateCodesAreRejected() {
        configService.seedDefaults();

        assertThatThrownBy(() -> configService.updateConfig(null, List.of("M_SCI_FI", "M_SCI_FI")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void invalidRankTypeIsRejected() {
        configService.seedDefaults();

        assertThatThrownBy(() -> configService.updateConfig("hot", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rankType");
        assertThat(configService.getActiveRankType()).isEqualTo("read");
    }

    /** 目录收缩后残留的旧 code 不参与展示，但排查接口仍能看到原值。 */
    @Test
    void displayedCategoriesSkipCodesMissingFromTheCatalog() {
        appConfigRepository.save(new org.example.fqxs.entity.AppConfig(
                ConfigService.KEY_DISPLAY_CATEGORIES, "M_SCI_FI,GHOST_CODE"));
        configService.refreshCache();

        assertThat(configService.getDisplayCategoryCodes()).containsExactly("M_SCI_FI", "GHOST_CODE");
        assertThat(configService.getDisplayedCategories()).containsExactly(CategoryCatalog.M_SCI_FI);
    }

    /** 库里的 rank_type 被绕过接口直接改脏（SQL 客户端手滑）时退回默认，
        而不是让所有抓取接口一起 500——loadSnapshot 的容错分支没有别处覆盖。 */
    @Test
    void dirtyRankTypeInDatabaseFallsBackToDefault() {
        configService.seedDefaults();
        appConfigRepository.save(new org.example.fqxs.entity.AppConfig(
                ConfigService.KEY_RANK_TYPE, "hot"));
        configService.refreshCache();

        assertThat(configService.getActiveRankType()).isEqualTo("read");
        assertThat(configService.getActiveRankMold()).isEqualTo(2);
    }
}
