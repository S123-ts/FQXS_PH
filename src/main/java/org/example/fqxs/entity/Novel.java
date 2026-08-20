// src/main/java/org/example/fqxs/entity/Novel.java
package org.example.fqxs.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "novel_rank",
        indexes = {@Index(name = "idx_category", columnList = "categoryName")})
@NoArgsConstructor
@AllArgsConstructor
public class Novel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String rank;

    @Column(length = 50, unique = true)
    private String bookId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 100)
    private String author;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String description;

    @Column(length = 20)
    private String status;

    @Column(length = 20)
    private String readCount;

    private Long readCountRaw;

    @Column(length = 200)
    private String lastChapter;

    @Column(length = 50)
    private String updateTime;

    @Column(length = 500)
    private String thumbUri;

    @Column(length = 50)
    private String categoryName;

    @Column(length = 20)
    private String wordCount;

    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime updateTimeDb;

    // ★★★ 新增：是否拉黑（默认 false） ★★★
    @Column(nullable = false)
    private Boolean isBlocked = false;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTimeDb = LocalDateTime.now();
        if (isBlocked == null) {
            isBlocked = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTimeDb = LocalDateTime.now();
    }
}