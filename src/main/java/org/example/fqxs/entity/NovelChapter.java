package org.example.fqxs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 章节正文缓存：目前只存「官方可读区（前 10 章）」这一档。
 *
 * <p>该表仅作缓存，不作业务主数据：{@code novel_rank} 是书籍本体。清空整表只会让下一次
 * 判定重新抓取上游，不影响结论，因此不设外键与级联。</p>
 *
 * <p>{@code pua_unresolved} 记的是<b>解码之后</b>仍残留的私用区字符数。它正常恒为 0；
 * 出现非 0 说明 {@code pua-charset.json} 已经落后于上游的混淆表，这是唯一的可观察信号。</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "novel_chapter",
        indexes = {
                @Index(name = "uk_novel_chapter_item", columnList = "item_id", unique = true),
                @Index(name = "idx_novel_chapter_book", columnList = "book_id, chapter_index")
        })
public class NovelChapter {

    /** 数据来源：官方阅读页 SSR。 */
    public static final String SOURCE_OFFICIAL = "official";

    /** 正文列宽，与迁移脚本的 VARCHAR(20000) 一致；超出截断，不静默丢整章。 */
    public static final int CONTENT_MAX_LENGTH = 20000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书籍 ID：与 novel_rank.book_id 一致，但不建外键（缓存不需要该约束）。 */
    @Column(name = "book_id", nullable = false, length = 50)
    private String bookId;

    /** 章节 ID：全局唯一，也是唯一键。 */
    @Column(name = "item_id", nullable = false, length = 50)
    private String itemId;

    /** 全书章号（0-based）：按书回放时用它排序，也用来回答「这一章是第几章」。 */
    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Column(name = "chapter_title", length = 200)
    private String chapterTitle;

    /** 清洗后的正文。 */
    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    /** 清洗后字数（按字符计，含标点），与 ChapterSample.chars 一致。 */
    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    /** 解码后仍残留的私用区字符数，正常为 0。 */
    @Column(name = "pua_unresolved", nullable = false)
    private Integer puaUnresolved = 0;

    @Column(nullable = false, length = 16)
    private String source = SOURCE_OFFICIAL;

    @Column(name = "fetched_time", nullable = false)
    private LocalDateTime fetchedTime;

    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
