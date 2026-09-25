package org.example.fqxs.model;

import java.util.List;

/** 单分类抓取结果：novels 是本次爬到的数据，dbSaved=false 表示未能写入数据库（仅影响下次“全部”视图）。 */
public record RankListResult(List<NovelInfo> novels, boolean dbSaved) {
}
