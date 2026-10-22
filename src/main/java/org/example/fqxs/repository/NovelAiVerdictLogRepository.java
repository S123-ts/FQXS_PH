package org.example.fqxs.repository;

import org.example.fqxs.entity.NovelAiVerdictLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NovelAiVerdictLogRepository extends JpaRepository<NovelAiVerdictLog, Long> {

    /** 详情面板用：某本书的全部轮次，新的在前。 */
    List<NovelAiVerdictLog> findByBookIdOrderByCheckedTimeDescIdDesc(String bookId);

    /** 裁剪用：只取主键，按新到旧排列，调用方砍掉超出保留上限的尾巴。 */
    @Query("SELECT l.id FROM NovelAiVerdictLog l WHERE l.bookId = :bookId ORDER BY l.id DESC")
    List<Long> findIdsByBookIdNewestFirst(@Param("bookId") String bookId);
}
