package org.example.fqxs.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 引擎路由的契约：显式指定不静默降级；随机优先避开已判引擎；
 * 候选全被排除时仍能返回一个（否则重判无法进行）。
 */
@ExtendWith(MockitoExtension.class)
class AiEngineRouterTest {

    @Mock
    private AiEngineRegistry registry;

    @InjectMocks
    private AiEngineRouter router;

    @Test
    void explicitEngineIsReturnedAsIs() {
        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash", "qwen2.5-7b"));

        assertThat(router.resolve("qwen2.5-7b")).isEqualTo("qwen2.5-7b");
    }

    @Test
    void unavailableExplicitEngineFailsInsteadOfSilentFallback() {
        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash"));

        assertThatThrownBy(() -> router.resolve("qwen2.5-7b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可用");
    }

    /** 自动停用的引擎要说清到期时间与原因，而不是笼统一句"不可用"。 */
    @Test
    void suspendedEngineExplainsWhyAndWhenItReturns() {
        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash"));
        when(registry.find("qwen2.5-7b")).thenReturn(java.util.Optional.of(
                org.example.fqxs.entity.AiEngine.builder()
                        .id("qwen2.5-7b").name("Qwen").baseUrl("https://x/chat/completions")
                        .model("Qwen/Qwen2.5-7B-Instruct").protocol("openai_chat")
                        .enabled(true).autoBan(true).priority(20)
                        .disableUntil(java.time.LocalDateTime.now().plusHours(40))
                        .disableReason("连续 3 次失败：上游返回 429")
                        .build()));

        assertThatThrownBy(() -> router.resolve("qwen2.5-7b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已自动停用至")
                .hasMessageContaining("连续 3 次失败")
                .hasMessageContaining("失败日志");
    }

    @Test
    void randomAvoidsEnginesThatAlreadyJudgedTheBook() {
        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash", "qwen2.5-7b"));

        for (int i = 0; i < 20; i++) {
            assertThat(router.resolveAvoiding("random", List.of("glm-4-flash")))
                    .isEqualTo("qwen2.5-7b");
        }
    }

    @Test
    void randomFallsBackToAllCandidatesWhenEverythingIsExcluded() {
        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash"));

        assertThat(router.resolveAvoiding("", List.of("glm-4-flash", "qwen2.5-7b")))
                .isEqualTo("glm-4-flash");
    }

    @Test
    void noAvailableEngineFailsWithActionableMessage() {
        when(registry.availableFreeEngines()).thenReturn(List.of());

        assertThatThrownBy(() -> router.resolve("random"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API 密钥");
    }

    @Test
    void randomAvailableNeedsAtLeastTwoEngines() {
        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash"));
        assertThat(router.randomAvailable()).isFalse();

        when(registry.availableFreeEngines()).thenReturn(List.of("glm-4-flash", "qwen2.5-7b"));
        assertThat(router.randomAvailable()).isTrue();
    }

    @Test
    void randomValueDetectionCoversNullBlankAndKeyword() {
        assertThat(AiEngineRouter.isRandom(null)).isTrue();
        assertThat(AiEngineRouter.isRandom(" ")).isTrue();
        assertThat(AiEngineRouter.isRandom("random")).isTrue();
        assertThat(AiEngineRouter.isRandom("RANDOM")).isTrue();
        assertThat(AiEngineRouter.isRandom("glm-4-flash")).isFalse();
    }
}
