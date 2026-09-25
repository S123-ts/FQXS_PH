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

    /** 长度不在此声明：由 db/schema-h2.sql 的 VARCHAR(20000) 决定（应用永不改库）。 */
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

    @Column(length = 20)
    private String wordCount;

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

    @PrePersist
    protected void onCreate() {
        createTime = DateUtil.now();
        updateTimeDb = DateUtil.now();
        if (isBlocked == null) {
            isBlocked = false;
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
