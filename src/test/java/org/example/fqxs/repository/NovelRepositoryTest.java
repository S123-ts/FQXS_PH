package org.example.fqxs.repository;

import jakarta.persistence.EntityManager;
import org.example.fqxs.entity.Novel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实的生产建表脚本跑两条手写 JPQL：CAST 数值排序与掉榜清理，
 * 这两处此前只有启动期解析校验。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fqxs-jpa-test;DB_CLOSE_DELAY=-1",
        "spring.test.database.replace=none",
        "spring.jpa.hibernate.ddl-auto=none"
})
class NovelRepositoryTest {

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private EntityManager entityManager;

    private Novel save(String bookId, String categoryCode, String rank, boolean blocked) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        novel.setTitle("书名-" + bookId);
        novel.setCategoryCode(categoryCode);
        novel.setGender(categoryCode.startsWith("F_") ? 0 : 1);
        novel.setRank(rank);
        novel.setIsBlocked(blocked);
        return novelRepository.saveAndFlush(novel);
    }

    private void clearContext() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void categoryQuerySortsRankNumerically() {
        save("b10", "M_WESTERN_FANTASY", "10", false);
        save("b2", "M_WESTERN_FANTASY", "2", false);
        save("b1", "M_WESTERN_FANTASY", "1", false);
        clearContext();

        List<String> ranks = novelRepository.findByCategoryCodeOrderByRankNumeric("M_WESTERN_FANTASY")
                .stream()
                .map(Novel::getRank)
                .toList();

        // 字典序会得到 1,10,2
        assertThat(ranks).containsExactly("1", "2", "10");
    }

    @Test
    void stalePruneKeepsBlockedRowsAndOtherCategories() {
        save("keep", "M_WESTERN_FANTASY", "1", false);
        save("stale", "M_WESTERN_FANTASY", "2", false);
        save("blocked-out", "M_WESTERN_FANTASY", "3", true);
        save("other-cat", "M_URBAN_HIGH_WU", "1", false);

        int deleted = novelRepository.deleteStaleByCategoryCodeAndRankType("M_WESTERN_FANTASY", "read",
                List.of("keep", "blocked-out"));
        clearContext();

        // 只删该分类该榜单分区里不在榜单且未拉黑的行
        assertThat(deleted).isEqualTo(1);
        assertThat(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn("M_WESTERN_FANTASY", "read",
                        List.of("keep", "stale", "blocked-out")))
                .extracting(Novel::getBookId)
                .containsExactlyInAnyOrder("keep", "blocked-out");
        assertThat(novelRepository.findByCategoryCodeInAndRankType(List.of("M_URBAN_HIGH_WU"), "read"))
                .extracting(Novel::getBookId)
                .containsExactly("other-cat");
    }

    /** 组合唯一键的另一半：清理一个分类时绝不能碰到同一本书在其他分类榜的行。 */
    @Test
    void stalePruneDoesNotTouchTheSameBookInAnotherCategory() {
        save("dup", "M_SCI_FI", "1", false);
        save("dup", "F_SCI_FI", "1", false);

        int deleted = novelRepository.deleteStaleByCategoryCodeAndRankType("M_SCI_FI", "read",
                List.of("someone-else"));

        assertThat(deleted).isEqualTo(1);
        assertThat(novelRepository.findByCategoryCodeInAndRankType(List.of("F_SCI_FI"), "read"))
                .extracting(Novel::getBookId)
                .containsExactly("dup");
    }

    /** 榜单类型分区：同分类同书在阅读榜/新书榜各占一行，互不覆盖也互不清掉。 */
    @Test
    void rankTypePartitionsKeepBothBoardsAndPruneIsolatesThem() {
        save("dup", "M_SCI_FI", "1", false);          // 默认 rankType=read
        Novel newRow = new Novel();
        newRow.setBookId("dup");
        newRow.setTitle("书名-dup");
        newRow.setCategoryCode("M_SCI_FI");
        newRow.setGender(1);
        newRow.setRank("2");
        newRow.setIsBlocked(false);
        newRow.setRankType("new");                    // 先改分区再落库，避免同分区冲突
        novelRepository.saveAndFlush(newRow);
        clearContext();

        assertThat(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn("M_SCI_FI", "read", List.of("dup")))
                .extracting(Novel::getRank)
                .containsExactly("1");
        assertThat(novelRepository.findByCategoryCodeAndRankTypeAndBookIdIn("M_SCI_FI", "new", List.of("dup")))
                .extracting(Novel::getRank)
                .containsExactly("2");

        // 清理新书榜分区绝不波及阅读榜的同一本书
        int deleted = novelRepository.deleteStaleByCategoryCodeAndRankType("M_SCI_FI", "new", List.of("gone"));
        assertThat(deleted).isEqualTo(1);
        assertThat(novelRepository.findByCategoryCodeInAndRankType(List.of("M_SCI_FI"), "read"))
                .extracting(Novel::getBookId)
                .containsExactly("dup");
    }
}
