package org.example.fqxs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次模型调用失败的事实留证。
 *
 * <p>回答「这个模型为什么不行」：上游状态码、失败分类、人可读的原因，以及最关键的
 * 上游返回片段——只有 message 一句话时，429 与 500 看不出区别，也没法复查上游改了什么。</p>
 *
 * <p>{@code responseExcerpt} 只存<b>响应体</b>片段并在入库前截断；请求头里的 Bearer 密钥
 * 不属于响应，不会进这一列。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_engine_fault")
public class AiEngineFault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 引擎标识（与判定明细同源，便于对着结论查失败）。 */
    @Column(nullable = false, length = 40)
    private String engine;

    /** 失败时使用的型号；型号级切换后，该列用于区分引擎故障与型号故障。 */
    @Column(length = 80)
    private String model;

    /** 失败分类：auth / rate / server / network / empty / parse / unknown。 */
    @Column(nullable = false, length = 20)
    private String kind;

    /** 上游 HTTP 状态码；非 HTTP 失败为 null。 */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** 人可读的失败摘要（已截断）。 */
    @Column(nullable = false, length = 300)
    private String reason;

    /** 上游返回片段（已截断）。 */
    @Column(name = "response_excerpt", length = 1000)
    private String responseExcerpt;

    /** 失败时判定的书籍 ID，便于复现。 */
    @Column(name = "book_id", length = 50)
    private String bookId;

    @Column(name = "occurred_time", nullable = false)
    private LocalDateTime occurredTime;
}
