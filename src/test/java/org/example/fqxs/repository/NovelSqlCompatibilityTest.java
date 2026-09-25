package org.example.fqxs.repository;

import org.example.fqxs.entity.Novel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 迁移后的 SQL 适配性测试：仓储里的每条语句（手写 JPQL + 派生查询）都打在
 * 真实建表脚本 `db/schema-h2.sql` 上，外加几处两个库行为可能不一致的地方
 * （三值逻辑、布尔列、时刻精度、列宽、唯一约束、非数值 rank）。
 * 唯一键是 (category_code, book_id) 组合：同书可挂多榜，NULL book_id 多枚共存。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fqxs-sql-compat;DB_CLOSE_DELAY=-1",
        "spring.test.database.replace=none",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-h2.sql"
})
class NovelSqlCompatibilityTest {

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private EntityManager entityManager;

    private Novel seed(String bookId, String categoryCode, int rank, boolean blocked) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        novel.setTitle("书-" + bookId);
        novel.setCategoryCode(categoryCode);
        novel.setGender(categoryCode.startsWith("F_") ? 0 : 1);
        novel.setRank(String.valueOf(rank));
        novel.setIsBlocked(blocked);
        novel.setReadCountRaw(1000L);
        return novelRepository.saveAndFlush(novel);
    }

    private void resetContext() {
        entityManager.flush();
        entityManager.clear();
    }

    /** bookId 不再全局唯一，这个助手只用于测试里恰好只有一行的场景。 */
    private Novel firstRow(String bookId) {
        List<Novel> rows = novelRepository.findAllByBookId(bookId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void derivedQueriesAllRoundTrip() {
        seed("a1", "M_WESTERN_FANTASY", 1, false);
        seed("a2", "M_URBAN_HIGH_WU", 2, false);
        seed("a3", "M_SCI_FI", 3, true);
        resetContext();

        assertThat(novelRepository.findAll()).hasSize(3);
        assertThat(novelRepository.findByCategoryCodeIn(List.of("M_WESTERN_FANTASY", "M_URBAN_HIGH_WU")))
                .extracting(Novel::getBookId).containsExactlyInAnyOrder("a1", "a2");
        assertThat(novelRepository.findByCategoryCodeAndBookIdIn("M_SCI_FI", List.of("a1", "a3", "missing")))
                .extracting(Novel::getBookId).containsExactly("a3");
        // IN 只有一个元素时仍要走通（Hibernate 不会退化成等值）
        assertThat(novelRepository.findByCategoryCodeAndBookIdIn("M_URBAN_HIGH_WU", List.of("a2"))).hasSize(1);
        assertThat(novelRepository.findByCategoryCodeIn(List.of())).isEmpty();
        assertThat(novelRepository.findAllByBookId("a1")).hasSize(1);
        assertThat(novelRepository.findAllByBookId("nope")).isEmpty();
    }

    /** 新列对实体透明：categoryCode 与 gender 原样往返。 */
    @Test
    void categoryCodeAndGenderColumnsRoundTrip() {
        seed("g1", "F_ERA", 1, false);
        seed("g2", "M_WAR_GOD", 1, false);
        resetContext();

        Novel female = firstRow("g1");
        assertThat(female.getCategoryCode()).isEqualTo("F_ERA");
        assertThat(female.getGender()).isZero();

        Novel male = firstRow("g2");
        assertThat(male.getCategoryCode()).isEqualTo("M_WAR_GOD");
        assertThat(male.getGender()).isEqualTo(1);
    }

    @Test
    void rankSortsNumericallyAcrossTheWholeList() {
        List<Novel> batch = new ArrayList<>();
        for (int rank = 1; rank <= 100; rank++) {
            Novel novel = new Novel();
            novel.setBookId("full-" + rank);
            novel.setTitle("整榜-" + rank);
            novel.setCategoryCode("M_WESTERN_FANTASY");
            novel.setGender(1);
            novel.setRank(String.valueOf(rank));
            novel.setIsBlocked(false);
            batch.add(novel);
        }
        novelRepository.saveAll(batch);
        seed("other", "M_URBAN_HIGH_WU", 1, false);
        resetContext();

        List<String> ranks = novelRepository.findByCategoryCodeOrderByRankNumeric("M_WESTERN_FANTASY")
                .stream().map(Novel::getRank).toList();

        // 字典序会把 10 排在 2 前、100 排在 10 前
        assertThat(ranks).hasSize(100);
        assertThat(ranks.indexOf("2")).isLessThan(ranks.indexOf("10"));
        assertThat(ranks.indexOf("10")).isLessThan(ranks.indexOf("100"));
        assertThat(novelRepository.findByCategoryCodeOrderByRankNumeric("M_URBAN_HIGH_WU")).hasSize(1);
        assertThat(novelRepository.findByCategoryCodeOrderByRankNumeric("M_NOT_EXIST")).isEmpty();
    }

    @Test
    void prunePredicateKeepsBlockedNullBookIdAndOtherCategories() {
        seed("keep", "M_WESTERN_FANTASY", 1, false);
        seed("stale", "M_WESTERN_FANTASY", 2, false);
        seed("blocked", "M_WESTERN_FANTASY", 3, true);
        seed("other", "M_URBAN_HIGH_WU", 4, false);
        // book_id 为 NULL 的行：NULL NOT IN (...) 得 UNKNOWN，两库都不会删它
        Novel nullBook = new Novel();
        nullBook.setTitle("无bookId");
        nullBook.setCategoryCode("M_WESTERN_FANTASY");
        nullBook.setGender(1);
        nullBook.setRank("5");
        nullBook.setIsBlocked(false);
        novelRepository.saveAndFlush(nullBook);
        resetContext();

        int deleted = novelRepository.deleteStaleByCategoryCode("M_WESTERN_FANTASY", List.of("keep", "blocked"));
        resetContext();

        assertThat(deleted).isEqualTo(1);
        assertThat(novelRepository.findAll()).extracting(Novel::getTitle)
                .containsExactlyInAnyOrder("书-keep", "书-blocked", "书-other", "无bookId");
    }

    @Test
    void pruneOnlySkipsRowsListedInKeep() {
        seed("k1", "M_WESTERN_FANTASY", 1, false);
        seed("k2", "M_WESTERN_FANTASY", 2, true);
        resetContext();

        assertThat(novelRepository.deleteStaleByCategoryCode("M_WESTERN_FANTASY", List.of("k1", "k2"))).isZero();
        resetContext();

        // 空 keep 列表不是"什么都不删"：x NOT IN (空集) 恒真，整分类的未拉黑行会被清空。
        // 所以 NovelServiceImpl 里 `pruneStale && !bookIds.isEmpty()` 那个判空是必需的，
        // 两库这点行为一致（SQL 标准语义），换 H2 没有改变风险面。
        assertThat(novelRepository.deleteStaleByCategoryCode("M_WESTERN_FANTASY", List.of())).isEqualTo(1);
        resetContext();
        assertThat(novelRepository.findAll()).extracting(Novel::getBookId).containsExactly("k2");
    }

    @Test
    void deleteByCategoryAndDeleteAll() {
        seed("c1", "M_WESTERN_FANTASY", 1, false);
        seed("c2", "M_WESTERN_FANTASY", 2, false);
        seed("c3", "M_URBAN_HIGH_WU", 1, false);

        assertThat(novelRepository.deleteByCategoryCode("M_URBAN_HIGH_WU")).isEqualTo(1);
        assertThat(novelRepository.deleteAllRecords()).isEqualTo(2);
        resetContext();
        assertThat(novelRepository.findAll()).isEmpty();
    }

    @Test
    void booleanFlagSurvivesBothDirections() {
        seed("off", "M_WESTERN_FANTASY", 1, false);
        seed("on", "M_WESTERN_FANTASY", 2, true);
        resetContext();

        Novel target = firstRow("off");
        target.setIsBlocked(true);
        target.setBlockedTime(LocalDateTime.of(2026, 9, 20, 12, 34, 56));
        novelRepository.saveAndFlush(target);
        resetContext();
        assertThat(firstRow("off").getIsBlocked()).isTrue();
        assertThat(firstRow("on").getBlockedTime()).isNull();

        target = firstRow("off");
        target.setIsBlocked(false);
        target.setBlockedTime(null);
        novelRepository.saveAndFlush(target);
        resetContext();
        assertThat(firstRow("off").getIsBlocked()).isFalse();
        assertThat(firstRow("off").getBlockedTime()).isNull();
    }

    @Test
    void mixedTextRoundTripsThroughH2() {
        String description = "【域降临全球】\n职业杨凌只是「」，松松。".repeat(30) + " tail";
        // 上游把书名/作者用 PUA 私用区码点混淆过，这里就是要确认 H2 原样存取这些码点
        String puaTitle = "东" + (char) 0xE3E8 + "西" + (char) 0xE4A1 + "PUA私用区";
        Novel novel = seed("text", "M_WESTERN_FANTASY", 1, false);
        novel.setTitle(puaTitle);
        novel.setAuthor("作者名");
        novel.setDescription(description);
        novel.setLastChapter("第1234章 · 大结局（上）");
        novel.setUpdateTime("2026-09-20 21:00:00");
        novel.setThumbUri("https://p3-reading-sign.fqnovelpic.com/cover.jpeg?x-expires=1&sig=A+B/c%20D");
        novel.setWordCount("123.4万");
        novel.setReadCount("1.2万");
        novel.setStatus("连载中");
        novelRepository.saveAndFlush(novel);
        resetContext();

        Novel read = firstRow("text");
        assertThat(read.getTitle()).isEqualTo(puaTitle).contains(String.valueOf((char) 0xE3E8));
        assertThat(read.getDescription()).isEqualTo(description);
        assertThat(read.getDescription()).hasSize(description.length());
        assertThat(read.getLastChapter()).contains("（上）");
        assertThat(read.getThumbUri()).contains("sig=A+B/c%20D");
        assertThat(read.getStatus()).isEqualTo("连载中");
        assertThat(read.getWordCount()).isEqualTo("123.4万");
    }

    @Test
    void prePersistTimestampsLandOnH2() {
        seed("stamp", "M_WESTERN_FANTASY", 1, false);
        resetContext();

        Novel read = firstRow("stamp");
        // @PrePersist 走 DateUtil.now()（固定北京时区），到秒必须无损
        assertThat(read.getCreateTime()).isNotNull();
        assertThat(read.getUpdateTimeDb()).isNotNull();
        assertThat(read.getCreateTime().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(read.getUpdateTimeDb().truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void h2TimestampColumnKeepsMillisecondsAndDropsNanoseconds() {
        seed("ts", "M_WESTERN_FANTASY", 1, false);
        resetContext();
        // createTime / updateTimeDb 被 @PrePersist/@PreUpdate 强制成 now()，
        // 只能绕开实体用原生 SQL 灌定值，才能验 H2 的时刻精度
        LocalDateTime stamp = LocalDateTime.of(2026, 9, 20, 8, 9, 10, 123_456_789);
        entityManager.createNativeQuery("UPDATE novel_rank SET update_time_db = ?1, blocked_time = ?1 "
                        + "WHERE book_id = 'ts'")
                .setParameter(1, java.sql.Timestamp.valueOf(stamp))
                .executeUpdate();
        resetContext();

        Novel read = firstRow("ts");
        assertThat(read.getUpdateTimeDb().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(stamp.truncatedTo(ChronoUnit.SECONDS));
        assertThat(read.getBlockedTime().truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(stamp.truncatedTo(ChronoUnit.SECONDS));
        // 精度只到微秒：纳秒位被截掉，业务按秒格式化所以无影响
        assertThat(read.getUpdateTimeDb().getNano() / 1_000_000).isEqualTo(123);
        assertThat(read.getUpdateTimeDb().getNano()).isNotEqualTo(123_456_789);
    }

    /** 同一分类里 bookId 冲突必须被唯一约束拦下，upsert 靠它保证不产生重复行。 */
    @Test
    void sameCategorySameBookIdConflicts() {
        seed("dup", "M_SCI_FI", 1, false);
        resetContext();

        assertThatThrownBy(() -> seed("dup", "M_SCI_FI", 1, false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 男女频共用 category_id：同一本书同时上两个分类榜必须能共存（本次重构的核心前提）。 */
    @Test
    void sameBookIdCoexistsAcrossCategories() {
        seed("dup", "M_SCI_FI", 3, false);
        seed("dup", "F_SCI_FI", 8, false);
        resetContext();

        assertThat(novelRepository.findAllByBookId("dup")).hasSize(2);
        assertThat(novelRepository.findByCategoryCodeIn(List.of("M_SCI_FI", "F_SCI_FI")))
                .extracting(Novel::getCategoryCode)
                .containsExactlyInAnyOrder("M_SCI_FI", "F_SCI_FI");
    }

    @Test
    void multipleNullBookIdsAreAllowed() {
        for (int i = 0; i < 2; i++) {
            Novel novel = new Novel();
            novel.setTitle("无主-" + i);
            novel.setCategoryCode("M_WESTERN_FANTASY");
            novel.setGender(1);
            novel.setRank("9");
            novel.setIsBlocked(false);
            novelRepository.saveAndFlush(novel);
        }
        resetContext();
        assertThat(novelRepository.findAll()).hasSize(2);
    }

    @Test
    void nonNumericRankFailsTheCategoryQuery() {
        Novel novel = seed("bad-rank", "M_WESTERN_FANTASY", 1, false);
        novel.setRank("10+");
        novelRepository.saveAndFlush(novel);
        resetContext();

        // CAST('10+' AS integer) 在 SQL Server 与 H2 里都会报错：整分类直接查不出来。
        // 上游若哪天把 rank 改成非纯数字，这里会先炸，不是静默错序。
        assertThatThrownBy(() -> novelRepository.findByCategoryCodeOrderByRankNumeric("M_WESTERN_FANTASY"))
                .isInstanceOf(Exception.class);
    }

    /** 历史回填 UPDATE 的谓词与幂等性：只补 NULL 行，回填过的行不再命中。 */
    @Test
    void backfillOnlyTouchesRowsWithoutACategoryCode() {
        seed("legacy", "M_WESTERN_FANTASY", 1, false);
        Novel legacy = firstRow("legacy");
        legacy.setCategoryCode(null);
        legacy.setCategoryName("西方奇幻");
        novelRepository.saveAndFlush(legacy);
        seed("modern", "M_WESTERN_FANTASY", 2, false);
        Novel orphan = new Novel();
        orphan.setTitle("乱数据");
        orphan.setCategoryName("不存在的分类");
        orphan.setGender(1);
        orphan.setRank("3");
        orphan.setIsBlocked(false);
        novelRepository.saveAndFlush(orphan);
        resetContext();

        assertThat(novelRepository.backfillCategoryCode("西方奇幻", "M_WESTERN_FANTASY")).isEqualTo(1);
        // 再跑一遍不再命中（幂等）
        assertThat(novelRepository.backfillCategoryCode("西方奇幻", "M_WESTERN_FANTASY")).isZero();
        resetContext();

        assertThat(firstRow("legacy").getCategoryCode())
                .isEqualTo("M_WESTERN_FANTASY");
        assertThat(firstRow("modern").getCategoryCode())
                .isEqualTo("M_WESTERN_FANTASY");
        // 乱数据行保持 NULL：映射不到就不进任何分类视图
        Number nullCodes = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM novel_rank WHERE category_code IS NULL")
                .getSingleResult();
        assertThat(nullCodes.longValue()).isEqualTo(1);
    }

    @Test
    void likeMatchingIsCaseSensitiveOnH2() {
        Novel novel = seed("case", "M_WESTERN_FANTASY", 1, false);
        novel.setTitle("ABC书名Def");
        novelRepository.saveAndFlush(novel);
        resetContext();

        // SQL Server 默认排序规则是 Chinese_PRC_CI_AS（大小写不敏感），H2 敏感：
        // 以后若加 DB 端英文关键词搜索，必须两边都 LOWER(...)，否则同一句 LIKE 两库结果不同。
        assertThat(likeCount("%ABC%")).isEqualTo(1);
        assertThat(likeCount("%Def%")).isEqualTo(1);
        assertThat(likeCount("%abc%")).isZero();
        assertThat(likeCount("%DEF%")).isZero();
        assertThat(lowerLikeCount("%def%")).isEqualTo(1);        // 中文不受大小写影响
        assertThat(likeCount("%书名%")).isEqualTo(1);
    }

    private long likeCount(String pattern) {
        Number count = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM novel_rank WHERE title LIKE ?1")
                .setParameter(1, pattern)
                .getSingleResult();
        return count.longValue();
    }

    private long lowerLikeCount(String pattern) {
        Number count = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM novel_rank WHERE LOWER(title) LIKE ?1")
                .setParameter(1, pattern)
                .getSingleResult();
        return count.longValue();
    }
}
