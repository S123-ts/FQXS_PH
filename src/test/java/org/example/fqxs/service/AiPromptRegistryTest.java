package org.example.fqxs.service;

import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiPrompt;
import org.example.fqxs.repository.AiPromptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 提示词库的路由契约：显式指定按指定走、随机模式按权重分摊、
 * 池空退回内置兜底、固定策略可复现。
 */
@ExtendWith(MockitoExtension.class)
class AiPromptRegistryTest {

    private static final String P1 = "p1-total";
    private static final String P7 = "p7-bagua";

    @Mock
    private AiPromptRepository repository;

    private AiDetectConfig config;

    private AiPromptRegistry registry;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        registry = new AiPromptRegistry(repository, config);
        lenient().when(repository.findByEnabledTrueAndPoolableTrueOrderBySortOrderAsc())
                .thenReturn(AiPromptTemplates.builtins().stream().filter(AiPrompt::isPoolable).toList());
    }

    /** 随机模式下多次取样要能覆盖到多个变体，而不是每次都是同一条。 */
    @Test
    void randomSpreadsAcrossPool() {
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(registry.pick(null).getId());
        }
        assertThat(picked).hasSizeGreaterThan(1);
        assertThat(picked).allMatch(id -> AiPromptTemplates.builtins().stream()
                .anyMatch(template -> template.getId().equals(id) && template.isPoolable()));
    }

    /** 权重大的变体中签率应高于权重小的：P1(100) 对比 P5(60)。 */
    @Test
    void higherWeightWinsMoreOften() {
        int p1Hits = 0;
        int p5Hits = 0;
        for (int i = 0; i < 600; i++) {
            String id = registry.pick("random").getId();
            if ("p1-total".equals(id)) {
                p1Hits++;
            } else if ("p5-fast".equals(id)) {
                p5Hits++;
            }
        }
        assertThat(p1Hits).isGreaterThan(p5Hits);
    }

    /** 显式指定必须按指定走，且回传的 id 就是它——否则分数无法追溯。 */
    @Test
    void explicitIdIsHonored() {
        when(repository.findById(P7)).thenReturn(Optional.of(prompt(P7, true, true, 90)));

        assertThat(registry.pick(P7).getId()).isEqualTo(P7);
    }

    /** 显式指定一个停用的变体要报错，不能静默换成别的。 */
    @Test
    void disabledExplicitIdFails() {
        when(repository.findById(P7)).thenReturn(Optional.of(prompt(P7, false, true, 90)));

        assertThatThrownBy(() -> registry.pick(P7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已停用");
    }

    /** 显式指定一个不存在的变体要报错。 */
    @Test
    void unknownExplicitIdFails() {
        lenient().when(repository.findById(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.pick("no-such-prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知提示词变体");
    }

    /** 随机池里的变体必须都是可随机的：P3/P4/P6 这类专项变体不能被抽中。 */
    @Test
    void nonPoolableNeverPicked() {
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            picked.add(registry.pick(null).getId());
        }
        assertThat(picked).doesNotContain("p3-anchor", "p4-single", "p6-rewrite");
    }

    /** 库为空（早于播种或被人清空）时退回内置版，而不是让整批判定失败。 */
    @Test
    void emptyPoolFallsBackToBuiltin() {
        when(repository.findByEnabledTrueAndPoolableTrueOrderBySortOrderAsc()).thenReturn(List.of());

        assertThat(registry.pick(null).getId()).isEqualTo(AiPromptTemplates.DEFAULT_ID);
    }

    /** 固定策略：结论可复现，多次取样必须是同一个变体。 */
    @Test
    void fixedStrategyIsReproducible() {
        config.setPromptStrategy("fixed");
        config.setDefaultPrompt(P1);
        when(repository.findById(P1)).thenReturn(Optional.of(prompt(P1, true, true, 100)));

        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            picked.add(registry.pick(null).getId());
        }
        assertThat(picked).containsExactly(P1);
    }

    /** 内置模板本身的自检：随机池必须有多个变体，且输出契约一致。 */
    @Test
    void builtinTemplatesAreConsistent() {
        List<AiPrompt> poolable = new ArrayList<>();
        for (AiPrompt template : AiPromptTemplates.builtins()) {
            assertThat(template.getContent()).isNotBlank();
            assertThat(template.getId()).startsWith("p");
            if (template.isPoolable()) {
                assertThat(template.getWeight()).isPositive();
                assertThat(template.getContent()).contains("ai_probability");
                poolable.add(template);
            }
        }
        assertThat(poolable).hasSizeGreaterThanOrEqualTo(3);
        // 注入防御是硬要求：缺了它，正文里写一句"忽略以上指令"就能把概率压下去
        for (AiPrompt template : AiPromptTemplates.builtins()) {
            if (template.isPoolable()) {
                assertThat(template.getContent()).contains("===TEXT_BEGIN===");
            }
        }
    }

    private AiPrompt prompt(String id, boolean enabled, boolean poolable, int weight) {
        AiPrompt record = new AiPrompt();
        record.setId(id);
        record.setName(id);
        record.setContent(AiPromptTemplates.P1_TOTAL);
        record.setEnabled(enabled);
        record.setPoolable(poolable);
        record.setWeight(weight);
        return record;
    }
}
