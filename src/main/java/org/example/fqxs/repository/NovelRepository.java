package org.example.fqxs.repository;

import org.example.fqxs.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NovelRepository extends JpaRepository<Novel, Long> {

    /**
     * rank 以字符串落库，直接 ORDER BY 会得到字典序（第 10 名排在第 2 名前），
     * 因此显式按数值排序。
     */
    @Query("SELECT n FROM Novel n WHERE n.categoryCode = :categoryCode ORDER BY CAST(n.rank AS integer) ASC")
    List<Novel> findByCategoryCodeOrderByRankNumeric(@Param("categoryCode") String categoryCode);

    List<Novel> findByCategoryCodeIn(Collection<String> categoryCodes);

    /** upsert 的批量预读：同一本书可挂在多个分类下，必须按 (categoryCode, bookId) 组合定位。 */
    List<Novel> findByCategoryCodeAndBookIdIn(String categoryCode, Collection<String> bookIds);

    /** 拉黑按书操作，同一本书在多个分类榜各有一行，返回全部行一起开关。 */
    List<Novel> findAllByBookId(String bookId);

    /**
     * 历史数据回填：category_code 仍为 NULL 的行按旧分类名补上新 code。
     * 幂等——回填过的行不再命中 IS NULL 谓词；映射不到的乱数据保持 NULL。
     */
    @Modifying
    @Query("UPDATE Novel n SET n.categoryCode = :code WHERE n.categoryName = :name AND n.categoryCode IS NULL")
    int backfillCategoryCode(@Param("name") String name, @Param("code") String code);

    /**
     * 清掉该分类中本次榜单已不再包含的掉榜行；调用方需保证传入的是完整榜单。
     * 已拉黑的行必须留下：拉黑是长期屏蔽名单，书掉榜不等于屏蔽失效。
     */
    @Modifying
    @Query("DELETE FROM Novel n WHERE n.categoryCode = ?1 AND n.bookId NOT IN ?2 AND n.isBlocked = false")
    int deleteStaleByCategoryCode(String categoryCode, Collection<String> keptBookIds);

    @Modifying
    @Query("DELETE FROM Novel")
    int deleteAllRecords();

    @Modifying
    @Query("DELETE FROM Novel n WHERE n.categoryCode = ?1")
    int deleteByCategoryCode(String categoryCode);
}
