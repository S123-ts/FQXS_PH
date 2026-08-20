// src/main/java/org/example/fqxs/service/RankService.java
package org.example.fqxs.service;

import org.example.fqxs.constant.CategoryConstant;
import org.example.fqxs.model.NovelInfo;

import java.util.List;

public interface RankService {

    List<NovelInfo> getRankList(CategoryConstant category, Integer page, Integer size);

    default List<NovelInfo> getRankList(CategoryConstant category) {
        return getRankList(category, 1, 30);
    }

    List<NovelInfo> getDefaultRankList();

    List<NovelInfo> getAllRankList();

    Integer getTotalCount(CategoryConstant category);
}