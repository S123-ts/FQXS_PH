package org.example.fqxs.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.fqxs.util.DateUtil;

import java.time.LocalDateTime;

/**
 * 判定流水：{@link NovelAiVerdict} 每次被覆盖前，先在这里留一条。
 *
 * <p>明细表的唯一键是 {@code (book_id, engine, model)}，同一型号重判会覆盖旧结论——
 * 那正是「这本书被 GLM 判过几次、每次多高」丢失的地方。这张表只追加不更新，
 * 详情面板按引擎把整条时间线摊开。</p>
 *
 * <p>保留上限由写入端裁剪（每本 {@link #KEEP_PER_BOOK} 条），避免批量初筛把表撑爆。</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "novel_ai_verdict_log",
        indexes = {@Index(name = "idx_verdict_log_book_time", columnList = "book_id, checked_time")})
public class NovelAiVerdictLog {

    /** 整本初筛（一次判定整本取样文本）。 */
    public static final String KIND_SWEEP = "sweep";

    /** 逐章校验的一步（每点一次推进一章）。 */
    public static final String KIND_VERIFY = "verify";

    /** 逐章校验已到上限，只推进进度不改结论。 */
    public static final String KIND_PROGRESS = "progress";

    /** 手点行内模型名（徽章）触发的强制重跑：与批量初筛区分开，详情里能看出是人手动发起的。 */
    public static final String KIND_RERUN = "rerun";

    /** 每本书保留的轮次上限：够覆盖「6 引擎 × 若干次重判」，再多只会在详情里刷屏。 */
    public static final int KEEP_PER_BOOK = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书籍 ID；跨分类行共用同一条时间线。 */
    @Column(name = "book_id", nullable = false, length = 50)
    private String bookId;

    /** 引擎标识：glm-4-flash / qianfan-ernie-speed / qwen2.5-7b / 用户自带 id / zhuque-text。 */
    @Column(nullable = false, length = 40)
    private String engine;

    /** 产出该结论的型号：同引擎换型号那一轮要能分辨出来。 */
    @Column(nullable = false, length = 80)
    private String model = "legacy";

    /** 该轮标记：1=非 AI 2=AI 3=疑似 AI（逐章进度行可能是 0）。 */
    @Column(name = "ai_flag", nullable = false)
    private Integer aiFlag = 0;

    /** 该轮给出的 AI 概率（0–100）。 */
    @Column(name = "ai_score")
    private Double aiScore;

    /** 该轮的判定理由摘要。 */
    @Column(length = 200)
    private String reason;

    /** 该轮使用的提示词变体 id。 */
    @Column(name = "prompt_id", length = 24)
    private String promptId;

    /** 该轮的取样档位（free/member + 策略）。 */
    @Column(name = "sample_mode", length = 24)
    private String sampleMode;

    /** 该轮实际采用的章号跨度。 */
    @Column(name = "sample_span", length = 40)
    private String sampleSpan;

    /** 该轮结束时逐章校验推进到第几章。 */
    @Column(name = "chapters_checked", nullable = false)
    private Integer chaptersChecked = 0;

    /**
     * 该轮实际校验的章号（1-based），只有逐章校验会填。
     *
     * <p>{@link #chaptersChecked} 是进度计数不是章号：取样档位是 recent/random 时
     * 进度 3 与章号 3 毫无关系，页面靠这一列才能说清「查的是第几章」。
     * 整本初筛与手点重跑没有单章概念，保持 null（跨度看 {@link #sampleSpan}）。</p>
     */
    @Column(name = "chapter_no")
    private Integer chapterNo;

    /** 该轮动作：{@link #KIND_SWEEP} / {@link #KIND_RERUN} / {@link #KIND_VERIFY} / {@link #KIND_PROGRESS}。 */
    @Column(nullable = false, length = 16)
    private String kind;

    /** 该轮发生时刻，由应用统一写北京时间。 */
    @Column(name = "checked_time", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkedTime;

    @PrePersist
    protected void onCreate() {
        if (checkedTime == null) {
            checkedTime = DateUtil.now();
        }
    }
}
