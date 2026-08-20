// src/main/java/org/example/fqxs/service/NovelService.java
package org.example.fqxs.service;

import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;

import java.util.List;

public interface NovelService {

    /** 单本保存（保留，内部调用增量更新） */
    void saveNovel(NovelInfo novelInfo);

    /** ★ 增量更新：存在则更新，不存在则插入，保留拉黑状态 */
    void saveOrUpdateNovelList(List<NovelInfo> novelList, String categoryName);

    /** 旧方法（保留兼容），内部调用 saveOrUpdateNovelList */
    default void saveNovelList(List<NovelInfo> novelList, String categoryName) {
        saveOrUpdateNovelList(novelList, categoryName);
    }

    List<Novel> findNovelsByCategory(String categoryName);

    List<Novel> findAllNovels();

    List<Novel> findAllBooksFiltered(List<String> categories, String wordRange, String status);

    void blockBook(String bookId);

    void deleteNovelsByCategory(String categoryName);

    void deleteAllNovels();
}