package org.example.fqxs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条判定提示词（system prompt 变体）。
 *
 * <p>判定链路不再把提示词写死在代码常量里：判定时从本表的启用池内随机取一条。
 * 单条提示词只能覆盖有限的特征面，固定用一条等于全库共用同一套盲区；
 * 多个变体换着用，判据视角就摊开了。</p>
 *
 * <p>{@code poolable=false} 的变体（需锚定样本、假设单章输入、不产出概率）
 * 只入库不参与随机，留给手动或专项场景调用。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_prompt")
public class AiPrompt {

    /** 变体标识，如 p1-total / p7-bagua。 */
    @Id
    @Column(length = 24)
    private String id;

    /** 面板展示名。 */
    @Column(nullable = false, length = 60)
    private String name;

    /** system prompt 全文；只进 system 位，正文永不写入本列。 */
    @Column(nullable = false, length = 4000)
    private String content;

    /** 来源出处：自建版本还是引自哪个开源方案。 */
    @Column(length = 200)
    private String origin;

    /** 内置变体不可删除。 */
    @Column(nullable = false)
    private boolean builtin;

    /** 停用后不参与随机与显式指定。 */
    @Column(nullable = false)
    private boolean enabled;

    /** 是否参与随机池。 */
    @Column(nullable = false)
    private boolean poolable;

    /** 随机权重，越大中签率越高。 */
    @Column(nullable = false)
    private int weight;

    /** 展示顺序。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 备注：适用场景与来源说明。 */
    @Column(length = 200)
    private String remark;
}
