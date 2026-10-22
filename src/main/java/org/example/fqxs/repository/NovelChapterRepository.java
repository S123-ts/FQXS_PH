package org.example.fqxs.repository;

import org.example.fqxs.entity.NovelChapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NovelChapterRepository extends JpaRepository<NovelChapter, Long> {

    /** 取章主路径：命中即用，不区分书籍（itemId 全局唯一）。 */
    Optional<NovelChapter> findByItemId(String itemId);

    /** 按书回放：诊断「这本书落了几章、章号对不对」用。 */
    List<NovelChapter> findByBookIdOrderByChapterIndexAsc(String bookId);

    long countByBookId(String bookId);
}
