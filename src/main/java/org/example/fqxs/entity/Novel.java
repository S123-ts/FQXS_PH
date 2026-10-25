package org.example.fqxs.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.example.fqxs.util.DateUtil;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "novel_rank",
        indexes = {@Index(name = "idx_category", columnList = "category_name")})
public class Novel {

    /** spring.jackson.date-format 管不到 JSR-310 类型，时刻格式只能逐字段标注。 */
    private static final String JSON_DATETIME = "yyyy-MM-dd HH:mm:ss";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 榜单名次，字符串存储以兼容历史表结构；排序在查询里按数值处理。 */
    @Column(nullable = false, length = 50)
    private String rank;

    /** 不再全局唯一：同一本书可同时挂在多个分类榜，唯一性由 (categoryCode, bookId) 承担。 */
    @Column(length = 50)
    private String bookId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String author;

    /** 长度不在此声明：由 db/migration/V1__baseline.sql 的 VARCHAR(20000) 决定（应用永不改库）。 */
    @Column
    private String description;

    @Column(length = 20)
    private String status;

    @Column(length = 20)
    private String readCount;

    @Column(name = "read_count_raw")
    private Long readCountRaw;

    @Column(length = 200)
    private String lastChapter;

    @Column(length = 50)
    private String updateTime;

    @Column(length = 500)
    private String thumbUri;

    @Column(length = 50)
    private String categoryName;

    /** 全局唯一分类码（M_/F_），与 bookId 组成业务唯一键；历史回填前的行为 NULL（不可见）。 */
    @Column(name = "category_code", length = 50)
    private String categoryCode;

    /** 上游 gender 参数：1=男频 0=女频；列默认 1，老库回填行自动落成男频。 */
    @Column(name = "gender")
    private Integer gender = 1;

    /** 榜单类型分区：read=阅读榜 new=新书榜。两种榜各自成快照互不覆盖，切榜直接读库；
     * 列默认 read，历史行都是在阅读榜下抓的，恰好落对区。 */
    @Column(name = "rank_type", length = 10)
    private String rankType = "read";

    @Column(length = 20)
    private String wordCount;

    /** 字数折成“万”的数值列：字符串无法在 SQL 里比较大小，筛选与排序靠它下推。 */
    @Column(name = "word_count_wan")
    private Double wordCountWan;

    /** 作者性别：0=女 / 1=男 / null=未取或第三方未知（2）。 */
    @Column(name = "author_gender")
    private Integer authorGender;

    /** 第三方评分（0–10），null=未取或该平台无评分。 */
    @Column
    private Double rating;

    /** 性别只取一次：有值即不再请求。 */
    @Column(name = "author_gender_checked_time")
    private LocalDateTime authorGenderCheckedTime;

    /** 评分刷新时间：超过 5 天才重新请求。 */
    @Column(name = "rating_checked_time")
    private LocalDateTime ratingCheckedTime;

    @Column(updatable = false)
    @JsonFormat(pattern = JSON_DATETIME)
    private LocalDateTime createTime;

    @Column(name = "update_time_db")
    @JsonFormat(pattern = JSON_DATETIME)
    private LocalDateTime updateTimeDb;

    /** 拉黑后不再出现在“全部”列表，重新爬取也不会被覆盖。 */
    @Column(nullable = false)
    private Boolean isBlocked = false;

    /** 拉黑动作发生的时刻，取消拉黑时清空；“已拉黑”列表按它倒序。 */
    @Column(name = "blocked_time")
    @JsonFormat(pattern = JSON_DATETIME)
    private LocalDateTime blockedTime;

    /**
     * 关注标记：与拉黑并列的第二种行内标记，按 bookId 全行联动。
     * 与 is_blocked 相互独立——关注是“留着看”，不影响正常列表的可见性。
     */
    @Column(name = "is_watched", nullable = false)
    private Boolean isWatched = false;

    /** 关注动作发生的时刻，取消关注时清空；“只看关注”列表按它倒序。 */
    @Column(name = "watched_time")
    @JsonFormat(pattern = JSON_DATETIME)
    private LocalDateTime watchedTime;

    /**
     * 免费引擎（GLM）的判定结论：0=未判 1=非 AI 2=AI 3=疑似 AI。
     * 与 is_blocked 同语义按 bookId 全行联动——同一本书在不同分类榜的行必须有同一结论。
     */
    @Column(name = "ai_flag", nullable = false)
    private Integer aiFlag = 0;

    /** 免费引擎给出的 AI 概率（0–100），仅作展示与排序，判定阈值在服务端。 */
    @Column(name = "ai_score")
    private Double aiScore;

    /** 产出该结论的引擎标识（glm-4-flash / zhuque-text），排查时能分辨结论来源。 */
    @Column(name = "ai_engine", length = 30)
    private String aiEngine;

    /** 免费引擎最近一次判定时刻；为空即“待初筛”，批量任务按它挑选目标。 */
    @Column(name = "ai_checked_time")
    @JsonFormat(pattern = JSON_DATETIME)
    private LocalDateTime aiCheckedTime;

    /** 朱雀判定结论：0=未判 1=已查章节均非 AI 2=命中 AI。命中后不再继续抽查。 */
    @Column(name = "zq_flag", nullable = false)
    private Integer zqFlag = 0;

    /** 朱雀最近一次给出的 AI 概率（0–100）。 */
    @Column(name = "zq_score")
    private Double zqScore;

    /** 朱雀已校验的章节数：每点一次推进一章，前端按它显示“n/10”进度。 */
    @Column(name = "zq_chapters_checked", nullable = false)
    private Integer zqChaptersChecked = 0;

    @Column(name = "zq_checked_time")
    @JsonFormat(pattern = JSON_DATETIME)
    private LocalDateTime zqCheckedTime;

    @PrePersist
    protected void onCreate() {
        createTime = DateUtil.now();
        updateTimeDb = DateUtil.now();
        if (isBlocked == null) {
            isBlocked = false;
        }
        if (isWatched == null) {
            isWatched = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTimeDb = DateUtil.now();
    }

    /** 实体只用主键判等，避免全字段 equals/hashCode 在插入前后抖动。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Novel other)) {
            return false;
        }
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : Objects.hash(id);
    }
}
