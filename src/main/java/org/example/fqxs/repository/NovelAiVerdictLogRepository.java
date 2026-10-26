package org.example.fqxs.repository;

import org.example.fqxs.entity.NovelAiVerdictLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NovelAiVerdictLogRepository extends JpaRepository<NovelAiVerdictLog, Long> {

    /** 详情面板用：某本书的全部轮次，新的在前。 */
    List<NovelAiVerdictLog> findByBookIdOrderByCheckedTimeDescIdDesc(String bookId);

    /**
     * 详情面板用（带上限）：某本书最近的若干轮，新的在前。
     *
     * <p>每本最多 {@code KEEP_PER_BOOK} 条，但一次全拉回来没必要——面板只摊开最近 100 轮。</p>
     */
    List<NovelAiVerdictLog> findByBookIdOrderByCheckedTimeDescIdDesc(String bookId, Pageable page);

    /** 裁剪用：只取主键，按新到旧排列，调用方砍掉超出保留上限的尾巴。 */
    @Query("SELECT l.id FROM NovelAiVerdictLog l WHERE l.bookId = :bookId ORDER BY l.id DESC")
    List<Long> findIdsByBookIdNewestFirst(@Param("bookId") String bookId);

    /** 裁剪前的短路判断：没超上限就不必把几百个 id 拉回来（逐章校验一轮会写 40 次）。 */
    long countByBookId(String bookId);
}
