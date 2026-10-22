package org.example.fqxs.model;

/** 单个分类的一次爬取汇总，用于“一键获取全部”的进度回执。 */
public record CategoryCrawlResult(
        String code,
        String name,
        Integer gender,
        Integer subcategoryId,
        int fetched,
        boolean dbSaved,
        String error
) {
    public boolean failed() {
        return error != null;
    }
}
