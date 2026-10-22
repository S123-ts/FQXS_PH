package org.example.fqxs.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.example.fqxs.util.DateUtil;

import java.time.LocalDateTime;

/**
 * 单引擎对单本书的判定明细。
 *
 * <p>主表 novel_rank 的 ai_* 只保留综合结论，逐引擎的原始结论在这里：
 * 同一本书被 GLM 和 Qwen 各判一次、再被朱雀抽一章，三份结论各自独立可追溯，
 * (book_id, engine) 唯一，重复判定覆盖旧行。</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "novel_ai_verdict")
public class NovelAiVerdict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书籍 ID；跨分类行共用同一份引擎结论。 */
    @Column(name = "book_id", nullable = false, length = 50)
    private String bookId;

    /** 引擎标识：glm-4-flash / qianfan-ernie-speed / qwen2.5-7b / 用户自带 id / zhuque-text。 */
    @Column(nullable = false, length = 40)
    private String engine;

    /** 产出该结论的型号；判断能否复用旧结论时必须连同型号一起比对。 */
    @Column(nullable = false, length = 80)
    private String model = "legacy";

    /** 该引擎的判定标记：1=非 AI 2=AI 3=疑似 AI（朱雀不产生 3）。 */
    @Column(name = "ai_flag", nullable = false)
    private Integer aiFlag = 0;

    /** 该引擎给出的 AI 概率（0–100）。 */
    @Column(name = "ai_score")
    private Double aiScore;

    /** 判定理由摘要；朱雀不给理由时为空。 */
    @Column(length = 200)
    private String reason;

    /** 产出该结论的提示词变体 id；提示词已入库随机调用，不记录就无法追溯结论出自哪个视角。 */
    @Column(name = "prompt_id", length = 24)
    private String promptId;

    /** 取样档位：free/member + 策略，如 member:mid_recent */
    @Column(name = "sample_mode", length = 24)
    private String sampleMode;

    /** 实际采用的章号跨度，如 1-4,7,12 */
    @Column(name = "sample_span", length = 40)
    private String sampleSpan;

    /** 逐章校验已推进到的章节数：按引擎独立记录进度，命中后不再推进。 */
    @Column(name = "chapters_checked", nullable = false)
    private Integer chaptersChecked = 0;

    /** 判定时刻，重复判定时覆盖。 */
    @Column(name = "checked_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkedTime;

    /** 新行落库时刻由应用统一写北京时间。 */
    @PrePersist
    protected void onCreate() {
        if (checkedTime == null) {
            checkedTime = DateUtil.now();
        }
    }
}
