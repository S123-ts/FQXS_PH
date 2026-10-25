package org.example.fqxs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.fqxs.util.DateUtil;

import java.time.LocalDateTime;

/**
 * 一本书的作者元数据：性别与评分，跟着 book_id 走、不跟着榜单走。
 *
 * <p>存在的理由：这两项只能靠第三方 {@code /info} 逐本取，一份约 19KB，共享机器容量很小。
 * 而 {@code novel_rank} 的行是榜单快照，掉榜就会被清理删掉——如果把作者信息只挂在榜单行上，
 * 书一旦掉榜再上榜，就得重新排队、再花一次额度。独立成表后掉榜只删快照，元数据留着。</p>
 *
 * <p>主键直接用 bookId（不是自增 id）：一个 book_id 只有一份元数据，没有第二行要区分。
 * 同一作者的多本书在第三方仍要逐本请求，所以这里不做 author 维度的聚合。</p>
 *
 * <p>{@code novel_rank} 上的 author_gender / rating 是展示冗余：页面筛选排序都在那张表上做。
 * 两边的同步点见 {@code NovelServiceImpl#applyMeta}（拉榜回填）与
 * {@code BookMetaEnricher#updateRows}（取到新值时双写）。</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "author_meta",
        indexes = {@Index(name = "idx_author_meta_gender_checked", columnList = "gender_checked_time")})
public class AuthorMeta {

    /** 书本 ID，与 novel_rank.book_id 同源。 */
    @Id
    @Column(name = "book_id", length = 50)
    private String bookId;

    /** 作者名快照：掉榜后榜单行没了，日志与排查仍需要知道这本书是谁写的。 */
    @Column(length = 100)
    private String author;

    /** 0=女 / 1=男 / null=取过但第三方没有可信值（profile_gender 缺失或为 2）。 */
    @Column
    private Integer gender;

    /** 性别只取一次：有值即不再请求；书已下架也盖章，避免反复试注定失败的请求。 */
    @Column(name = "gender_checked_time")
    private LocalDateTime genderCheckedTime;

    /** 第三方评分（0–10），null=取过但该平台无评分。 */
    @Column
    private Double rating;

    /** 评分按 rating-refresh-days（默认 5 天）刷新。 */
    @Column(name = "rating_checked_time")
    private LocalDateTime ratingCheckedTime;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time_db")
    private LocalDateTime updateTimeDb;

    @PrePersist
    void onCreate() {
        LocalDateTime now = DateUtil.now();
        if (createTime == null) {
            createTime = now;
        }
        updateTimeDb = now;
    }

    @PreUpdate
    void onUpdate() {
        updateTimeDb = DateUtil.now();
    }
}
