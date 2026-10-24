package org.example.fqxs.repository;

import jakarta.persistence.EntityManager;
import org.example.fqxs.entity.Novel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

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

    /** 搜索：书名或作者命中；排序「精确 → 前缀 → 包含 → 作者」，同档热度降序。 */
    @Test
    void searchMatchesTitleOrAuthorAndRanksExactFirst() {
        saveSearch("exact", "游戏", "张三", 100, false, "M_SCI_FI");
        saveSearch("prefix", "游戏人生", "李四", 9000, false, "M_SCI_FI");
        saveSearch("contains", "这个游戏不对劲", "王五", 500, false, "M_SCI_FI");
        saveSearch("author-hit", "另一个故事", "游戏迷", 8000, false, "M_SCI_FI");
        saveSearch("blocked-hit", "游戏被拉黑", "赵六", 9999, true, "M_SCI_FI");
        clearContext();

        List<Novel> rows = novelRepository.searchByKeyword("游戏", "%游戏%", "游戏%",
                PageRequest.of(0, 20));

        // 精确书名第一，其次前缀，再次包含，最后作者命中；被拉黑的书不出现
        assertThat(rows).extracting(Novel::getBookId)
                .containsExactly("exact", "prefix", "contains", "author-hit");
    }

    /** 同一本书挂在多个分类榜各一行：搜索结果按 bookId 只出一条，总数也按书去重。 */
    @Test
    void searchDeduplicatesBooksAcrossCategories() {
        saveSearch("dup", "重复之书", "作者", 100, false, "M_SCI_FI");
        saveSearch("dup", "重复之书", "作者", 100, false, "F_SCI_FI");
        saveSearch("other", "重复之书外传", "作者", 50, false, "M_SCI_FI");
        clearContext();

        List<Novel> rows = novelRepository.searchByKeyword("重复", "%重复%", "重复%",
                PageRequest.of(0, 20));

        assertThat(rows).extracting(Novel::getBookId).containsExactly("dup", "other");
        assertThat(novelRepository.countSearchKeyword("%重复%")).isEqualTo(2);
    }

    /** 转义后的 % 只当普通字符：否则一次「100%」搜索会变成全表匹配。 */
    @Test
    void escapedWildcardsAreMatchedLiterally() {
        saveSearch("literal", "100%纯爱", "作者", 10, false, "M_SCI_FI");
        saveSearch("decoy", "100次心动", "作者", 20, false, "M_SCI_FI");
        clearContext();

        List<Novel> rows = novelRepository.searchByKeyword("100%", "%100!%%", "100!%%",
                PageRequest.of(0, 20));

        assertThat(rows).extracting(Novel::getBookId).containsExactly("literal");
    }

    /** 没有 bookId 的历史脏行无法打开详情，不参与搜索。 */
    @Test
    void searchSkipsRowsWithoutBookId() {
        Novel orphan = new Novel();
        orphan.setTitle("无编号的书");
        orphan.setAuthor("作者");
        orphan.setRank("1");
        orphan.setIsBlocked(false);
        novelRepository.saveAndFlush(orphan);
        saveSearch("numbered", "有编号的书", "作者", 1, false, "M_SCI_FI");
        clearContext();

        assertThat(novelRepository.searchByKeyword("书", "%书%", "书%", PageRequest.of(0, 20)))
                .extracting(Novel::getBookId)
                .containsExactly("numbered");
    }

    /** 分页只切本页，count 仍是全部命中数——书源消费方靠它判断有没有下一页。 */
    @Test
    void searchPagesRowsButCountsAllMatches() {
        for (int index = 1; index <= 5; index++) {
            saveSearch("b" + index, "分页书" + index, "作者", 100 - index, false, "M_SCI_FI");
        }
        clearContext();

        List<Novel> first = novelRepository.searchByKeyword("分页", "%分页%", "分页%", PageRequest.of(0, 2));
        List<Novel> second = novelRepository.searchByKeyword("分页", "%分页%", "分页%", PageRequest.of(1, 2));

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(first).extracting(Novel::getBookId)
                .doesNotContainAnyElementsOf(second.stream().map(Novel::getBookId).toList());
        assertThat(novelRepository.countSearchKeyword("%分页%")).isEqualTo(5);
    }

    private Novel saveSearch(String bookId, String title, String author, long readers,
                             boolean blocked, String categoryCode) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        novel.setTitle(title);
        novel.setAuthor(author);
        novel.setReadCountRaw(readers);
        novel.setCategoryCode(categoryCode);
        novel.setGender(categoryCode.startsWith("F_") ? 0 : 1);
        novel.setRank("1");
        novel.setIsBlocked(blocked);
        return novelRepository.saveAndFlush(novel);
    }
}
