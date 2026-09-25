package org.example.fqxs.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NovelInfo {
    private String rank;
    private String bookId;
    private String title;
    private String author;
    private String description;
    private String status;
    private String readCount;
    private Long readCountRaw;
    private String lastChapter;
    private String updateTime;
    private String thumbUri;
    private String categoryName;
    /** 全局唯一分类码（M_/F_），落库与前端展示都以它定位分类。 */
    private String categoryCode;
    /** 1=男频 0=女频，来自分类目录而非上游响应。 */
    private Integer gender;
    private String wordCount;
}
