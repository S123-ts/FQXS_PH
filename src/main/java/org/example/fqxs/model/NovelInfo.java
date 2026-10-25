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

    /**
     * 首次入榜时刻（对应 novel_rank.create_time，格式 {@code yyyy-MM-dd HH:mm:ss}）。
     *
     * <p>上游响应里没有这个字段——它是入库时由 {@code @PrePersist} 盖的时间戳。
     * 抓取即写库，因此 {@code RankServiceImpl.persist} 成功后按 bookId 回查回填；
     * 数据库不可用时留空，前端「7 天内新加入榜单」徽章据此不亮。</p>
     */
    private String createTime;
}
