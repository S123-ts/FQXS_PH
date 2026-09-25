package org.example.fqxs.service;

import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;

import java.util.List;

public interface NovelService {

    /**
     * 增量更新：按 (categoryCode, bookId) 定位，存在则更新、不存在则插入，保留拉黑状态；
     * 返回本次落库统计。pruneStale 为 true 时同时清掉该分类中本次榜单已不包含的掉榜行，
     * 只有抓到完整榜单才允许传 true，否则一页数据会把其余名次误删。
     */
    UpsertSummary saveOrUpdateNovelList(List<NovelInfo> novelList, CategoryCatalog category, boolean pruneStale);

    List<Novel> findNovelsByCategoryCode(String categoryCode);

    List<Novel> findAllNovels();

    /** blockedOnly 为 true 时反过来只返回已拉黑的书，给“恢复”留出口。 */
    List<Novel> findAllBooksFiltered(List<String> categoryCodes, String wordRange, String status, boolean blockedOnly);

    /** 同一本书可能挂多个分类榜，拉黑/恢复作用于它的全部行。 */
    void setBlocked(String bookId, boolean blocked);

    void deleteNovelsByCategoryCode(String categoryCode);

    void deleteAllNovels();

    /** 写库结果统计，skipped 用于暴露被丢弃的脏数据（如缺失 bookId）。 */
    record UpsertSummary(int inserted, int updated, int skipped) {
        public static final UpsertSummary EMPTY = new UpsertSummary(0, 0, 0);
    }
}
