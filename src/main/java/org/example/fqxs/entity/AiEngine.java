package org.example.fqxs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.fqxs.util.DateUtil;

import java.time.LocalDateTime;

/**
 * 一个可调用的 AI 引擎（内置免费渠道或用户自带的 OpenAI 兼容端点）。
 *
 * <p>id 创建后不可改：判定明细按 engine 关联历史结论。密钥不在这张表里，
 * 仍只存 data/ai-keys.properties。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_engine")
public class AiEngine {

    /** 引擎标识，创建后不可变（内置沿用 glm-4-flash / qwen2.5-7b / zhuque-text）。 */
    @Id
    @Column(length = 40)
    private String id;

    /** 面板展示名。 */
    @Column(nullable = false, length = 60)
    private String name;

    /** OpenAI 兼容 chat 端点（朱雀为 classify 端点）。 */
    @Column(name = "base_url", nullable = false, length = 300)
    private String baseUrl;

    /** 当前选中型号；朱雀为空。 */
    @Column(nullable = false, length = 80)
    private String model;

    /** 可选型号清单，逗号分隔（拉取或手填）。 */
    @Column(name = "models_csv", length = 2000)
    private String modelsCsv;

    /** 勾选的型号池，逗号分隔：判定时随机取一个；为空则只用 {@link #model}。 */
    @Column(name = "model_pool", length = 600)
    private String modelPool;

    /** openai_chat（通用通道）或 classify（朱雀私有格式，不并入通用分支）。 */
    @Column(nullable = false, length = 20)
    private String protocol;

    /** 内置引擎不可删除。 */
    @Column(nullable = false)
    private boolean builtin;

    /** 停用后不参与判定与熔断候选。 */
    @Column(nullable = false)
    private boolean enabled;

    /** 是否允许在连续失败时被熔断换掉。 */
    @Column(name = "auto_ban", nullable = false)
    private boolean autoBan;

    /** 熔断切换顺序，小的先试。 */
    @Column(nullable = false)
    private int priority;

    /** 备注：权限要求、免费档说明等。 */
    @Column(length = 200)
    private String remark;

    /** 连续失败自动停用的到期时间；null 或已过期 = 可用。落库所以重启不丢。 */
    @Column(name = "disable_until")
    private LocalDateTime disableUntil;

    /** 为什么被停用（哪一类失败、累计几次），给日志页面展示。 */
    @Column(name = "disable_reason", length = 300)
    private String disableReason;

    /** 连续失败次数：判定成功即清零，达到阈值触发上面的停用。 */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    /** 是否 OpenAI 兼容通道（可参与批量初筛与校验本页）。 */
    public boolean isOpenAiCompatible() {
        return "openai_chat".equals(protocol);
    }

    /** 是否仍在自动停用期内（与用户手动 enabled=false 无关，到期自动恢复）。 */
    public boolean isSuspended() {
        return disableUntil != null && disableUntil.isAfter(DateUtil.now());
    }
}
