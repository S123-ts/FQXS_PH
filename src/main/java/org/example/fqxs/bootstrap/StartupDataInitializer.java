package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.ConfigService;
import org.example.fqxs.util.PuaDecoder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动收尾，全部幂等（重复启动无副作用，跑两遍结果一致）：
 * 1. 历史数据回填：category_code 尚为 NULL 的行按 category_name 对男频目录的 19 个名字
 *    映射出 code（现库历史上只有男频榜；含曾退役又复出的“都市修真”=124）。
 *    映射不到的乱数据保持 NULL，即不出现在任何分类视图里，等人工处理。
 *    gender 列不用回填：加列时的 DEFAULT 1 已把历史行落成男频。
 * 2. 配置播种：app_config 里缺哪个键就补哪个键的默认值，已有配置（含空串）不动。
 * 3. 历史文本还原：早于入库解码上线时落库的书名/作者/简介是上游 PUA 混淆文本，按字符表还原成汉字。
 */
@Slf4j
@Component
@Order(100) // 必须晚于 SchemaInitializer（建表先行，回填/播种才有表）
@RequiredArgsConstructor
public class StartupDataInitializer implements ApplicationRunner {

    private final NovelRepository novelRepository;
    private final ConfigService configService;
    private final PuaDecoder puaDecoder;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // ApplicationRunner 抛异常会终止整个启动，而“数据库不可用时应用要照常起来、
        // 读接口 503”是 HANDOVER §3 的契约；回填与播种全部幂等，这次失败下次启动自动补上。
        // Tomcat 先于 runner 开始监听，窗口内 meta 可能拿到未回填的空配置，完成后缓存自愈。
        try {
            // @Modifying JPQL 需要事务包裹（HANDOVER §12），不能只靠方法上的 @Transactional：
            // 事务开启本身在库不可用时就抛，try/catch 必须罩住整个事务
            transactionTemplate.executeWithoutResult(tx -> {
                backfillCategoryCodes();
                backfillPuaText();
                configService.seedDefaults();
            });
        } catch (Exception e) {
            log.error("启动初始化失败（数据库不可用？），已跳过，下次启动重试: {}", e.getMessage());
        }
    }

    private void backfillCategoryCodes() {
        int total = 0;
        // 只对男频目录回填：女频与男频共用部分名字（悬疑脑洞等），历史行全是男频榜
        for (CategoryCatalog category : CategoryCatalog.values()) {
            if (category.getGender() != 1) {
                continue;
            }
            total += novelRepository.backfillCategoryCode(category.getName(), category.getCode());
        }
        if (total > 0) {
            log.info("历史数据回填完成: {} 行补上 category_code", total);
        }
    }

    /**
     * 把已入库的 PUA 混淆文本还原成汉字。
     *
     * <p>用 JDBC 批量 UPDATE 而不是走实体：实体上的 {@code @PreUpdate} 会顺手刷新
     * {@code update_time_db}，而回填只是把同一份内容换个写法，不该被记成一次数据更新。
     * 只碰四个文本列，索引列与 AI 判定列一概不写。</p>
     *
     * <p>幂等：还原后的文本不含 PUA，下次启动整表扫一遍会直接命中 0 行、不发任何 UPDATE。</p>
     */
    private void backfillPuaText() {
        List<TextRow> rows = jdbcTemplate.query(
                "SELECT id, title, author, description, last_chapter FROM novel_rank",
                (rs, rowNum) -> new TextRow(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("description"),
                        rs.getString("last_chapter")));

        List<Object[]> batch = new ArrayList<>();
        for (TextRow row : rows) {
            boolean polluted = PuaDecoder.containsPua(row.title())
                    || PuaDecoder.containsPua(row.author())
                    || PuaDecoder.containsPua(row.description())
                    || PuaDecoder.containsPua(row.lastChapter());
            if (!polluted) {
                continue;
            }
            batch.add(new Object[]{
                    puaDecoder.decodeIfNeeded(row.title()),
                    puaDecoder.decodeIfNeeded(row.author()),
                    puaDecoder.decodeIfNeeded(row.description()),
                    puaDecoder.decodeIfNeeded(row.lastChapter()),
                    row.id()});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "UPDATE novel_rank SET title = ?, author = ?, description = ?, last_chapter = ? WHERE id = ?",
                batch);
        log.info("PUA 文本回填完成: {}/{} 行的书名、作者、简介还原为汉字", batch.size(), rows.size());
    }

    /** 回填只用到四个文本列，不加载整实体；包级可见以便测试直接构造。 */
    record TextRow(long id, String title, String author, String description, String lastChapter) {
    }
}
