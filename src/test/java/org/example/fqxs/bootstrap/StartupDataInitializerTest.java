package org.example.fqxs.bootstrap;

import org.example.fqxs.entity.AppConfig;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.repository.AppConfigRepository;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动 runner 的两个职责都必须幂等（重复启动/重复执行结果一致）：
 * 历史行按男频目录名字回填 category_code，映射不到的保持 NULL；app_config 播种默认值。
 * 注意 @SpringBootTest 启动时 runner 已对空表跑过一遍，这里再灌历史数据手动跑两遍。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fqxs-backfill;DB_CLOSE_DELAY=-1")
class StartupDataInitializerTest {

    @Autowired
    private StartupDataInitializer initializer;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private AppConfigRepository appConfigRepository;

    @Autowired
    private ConfigService configService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private Novel legacyRow(String bookId, String categoryName, int rank) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        novel.setTitle("历史-" + bookId);
        novel.setCategoryName(categoryName);
        novel.setCategoryCode(null);
        novel.setGender(1);
        novel.setRank(String.valueOf(rank));
        novel.setIsBlocked(false);
        return novel;
    }

    private Novel firstRow(String bookId) {
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void seedLegacyRows() {
        inTx(() -> novelRepository.saveAll(List.of(
                legacyRow("w1", "西方奇幻", 1),
                legacyRow("c1", "都市修真", 1),      // 曾退役、如今重新成为真实分类 id=124
                legacyRow("f1", "都市种田", 1),
                legacyRow("s1", "科幻末世", 1),
                legacyRow("g1", "不存在的乱数据", 1))));
    }

    @Test
    void legacyMaleCategoryNamesAreBackfilledByIdempotently() {
        seedLegacyRows();

        initializer.run(null);
        assertBackfilled();
        long countAfterFirstRun = novelRepository.count();

        // 跑两遍结果一致：第二遍不再命中任何行
        initializer.run(null);
        assertBackfilled();
        assertThat(novelRepository.count()).isEqualTo(countAfterFirstRun);
    }

    private void assertBackfilled() {
        // runner 的事务已提交；仓库读各自开新持久化上下文，读到的是最新数据
        assertThat(firstRow("w1").getCategoryCode())
                .isEqualTo("M_WESTERN_FANTASY");
        assertThat(firstRow("c1").getCategoryCode())
                .isEqualTo("M_URBAN_CULTIVATION");
        assertThat(firstRow("f1").getCategoryCode())
                .isEqualTo("M_URBAN_FARMING");
        assertThat(firstRow("s1").getCategoryCode())
                .isEqualTo("M_SCI_FI");
        // 映射不到的乱数据保持 NULL：不进任何分类视图
        assertThat(firstRow("g1").getCategoryCode()).isNull();
        assertThat(firstRow("g1").getGender()).isEqualTo(1);
    }

    @Test
    void appConfigIsSeededWithDefaultsAndReseedingKeepsUserValues() {
        initializer.run(null);

        assertThat(appConfigRepository.findById(ConfigService.KEY_RANK_TYPE))
                .hasValueSatisfying(config -> assertThat(config.getCfgValue()).isEqualTo("read"));
        assertThat(appConfigRepository.findById(ConfigService.KEY_DISPLAY_CATEGORIES))
                .hasValueSatisfying(config -> assertThat(config.getCfgValue())
                        .isEqualTo(ConfigService.DEFAULT_DISPLAY_CATEGORIES));

        // 用户已改过的配置不会被播种覆盖
        appConfigRepository.save(new AppConfig(ConfigService.KEY_RANK_TYPE, "new"));
        initializer.run(null);
        assertThat(configService.getActiveRankType()).isEqualTo("new");
    }
}
