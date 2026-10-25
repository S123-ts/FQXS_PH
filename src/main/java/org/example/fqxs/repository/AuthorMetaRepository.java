package org.example.fqxs.repository;

import org.example.fqxs.entity.AuthorMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 作者元数据仓储：只按 bookId 精确取，没有模糊查询。
 *
 * <p>「哪些书还缺作者信息」这个判断需要把 {@code novel_rank} 与这张表连起来看，
 * 语句写在 {@link NovelRepository#findBookIdsNeedingMeta}（原生 SQL），不在这里出。
 * 本接口只负责「给定一批 bookId，把已有的元数据一次性取回」，供拉榜回填与补齐排队使用。</p>
 */
@Repository
public interface AuthorMetaRepository extends JpaRepository<AuthorMeta, String> {

    /** 批量预读：一次 IN 查代替逐本 findById，拉榜写库与补齐排队都走它。 */
    List<AuthorMeta> findAllByBookIdIn(Collection<String> bookIds);
}
