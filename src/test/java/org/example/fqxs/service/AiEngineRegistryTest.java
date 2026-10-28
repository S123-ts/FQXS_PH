package org.example.fqxs.service;

import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.repository.AiEngineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 型号池契约：池为空退化为单型号；随机取值不越出勾选集；保存时去重并同步主型号。
 */
@ExtendWith(MockitoExtension.class)
class AiEngineRegistryTest {

    @Mock
    private AiEngineRepository repository;

    @Mock
    private AiKeyStore keyStore;

    private AiEngineRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AiEngineRegistry(repository, keyStore);
    }

    private static AiEngine engine(String model, String pool) {
        return AiEngine.builder()
                .id("glm-4-flash").name("智谱 GLM").baseUrl("https://example.test/v1/chat/completions")
                .model(model).modelPool(pool).modelsCsv("glm-4-flash,glm-4.7-flash,glm-4.5-flash")
                .protocol("openai_chat").builtin(true).enabled(true).autoBan(true).priority(0)
                .build();
    }

    @Test
    void emptyPoolFallsBackToSingleModel() {
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(engine("glm-4-flash", null)));

        assertThat(registry.modelsOf("glm-4-flash")).containsExactly("glm-4-flash");
        assertThat(registry.primaryModel("glm-4-flash")).isEqualTo("glm-4-flash");
    }

    @Test
    void poolIsParsedAndTrimmed() {
        when(repository.findById("glm-4-flash"))
                .thenReturn(Optional.of(engine("glm-4-flash", "glm-4-flash, glm-4.7-flash ,,glm-4.5-flash")));

        assertThat(registry.modelsOf("glm-4-flash"))
                .containsExactly("glm-4-flash", "glm-4.7-flash", "glm-4.5-flash");
    }

    @Test
    void updateModelsDeduplicatesAndSetsPrimary() {
        AiEngine record = engine("glm-4-flash", null);
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        registry.updateModels("glm-4-flash", List.of("glm-4.7-flash", "glm-4.7-flash", "glm-4.5-flash"));

        assertThat(record.getModelPool()).isEqualTo("glm-4.7-flash,glm-4.5-flash");
        assertThat(record.getModel()).isEqualTo("glm-4.7-flash");
        verify(repository).save(record);
    }

    @Test
    void emptyPoolSelectionClearsPoolAndKeepsPrimary() {
        AiEngine record = engine("glm-4-flash", "glm-4-flash,glm-4.7-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        registry.updateModels("glm-4-flash", List.of());

        assertThat(record.getModelPool()).isEmpty();
        assertThat(record.getModel()).isEqualTo("glm-4-flash");
        assertThat(registry.modelsOf("glm-4-flash")).containsExactly("glm-4-flash");
        verify(repository).save(record);
    }

    @Test
    void setEnabledTogglesWithoutTouchingModelsOrKey() {
        AiEngine record = engine("glm-4-flash", "glm-4-flash,glm-4.7-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        registry.setEnabled("glm-4-flash", false);

        assertThat(record.isEnabled()).isFalse();
        assertThat(record.getModel()).isEqualTo("glm-4-flash");
        assertThat(record.getModelPool()).isEqualTo("glm-4-flash,glm-4.7-flash");
        verify(repository).save(record);
        verify(keyStore, never()).save(any(), any());
    }

    @Test
    void singleModelSwitchClearsThePool() {
        AiEngine record = engine("glm-4-flash", "glm-4-flash,glm-4.7-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        registry.updateModel("glm-4-flash", "glm-4.5-flash");

        assertThat(record.getModel()).isEqualTo("glm-4.5-flash");
        assertThat(record.getModelPool()).isNull();
        verify(repository).save(any(AiEngine.class));
    }

    @Test
    void updateModelsAddsUnknownModelsToCatalog() {
        AiEngine record = engine("glm-4-flash", null);
        record.setModelsCsv("glm-4-flash,glm-4.7-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        registry.updateModels("glm-4-flash", List.of("glm-5.3-flash", "glm-4.7-flash"));

        assertThat(record.getModelPool()).isEqualTo("glm-5.3-flash,glm-4.7-flash");
        assertThat(record.getModelsCsv()).contains("glm-4.7-flash").contains("glm-5.3-flash");
    }

    @Test
    void rejectsSpoofedLoopbackAndPrivateHosts() {
        assertThatThrownBy(() -> AiEngineRegistry.normalizeBaseUrl("http://localhost.evil.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEngineRegistry.normalizeBaseUrl("http://127.0.0.1.attacker.tld/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEngineRegistry.normalizeBaseUrl("https://192.168.1.10/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEngineRegistry.normalizeBaseUrl("https://169.254.169.254/latest"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsLoopbackAndNormalizesHttpsEndpoints() {
        assertThat(AiEngineRegistry.normalizeBaseUrl("http://localhost:11434/v1"))
                .isEqualTo("http://localhost:11434/v1/chat/completions");
        assertThat(AiEngineRegistry.normalizeBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(AiEngineRegistry.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(AiEngineRegistry.normalizeBaseUrl("https://api.siliconflow.cn/v1/chat/completions"))
                .isEqualTo("https://api.siliconflow.cn/v1/chat/completions");
    }

    /**
     * 内置引擎端点锁定（安全测试报告 M6）：改端点 = 让服务器把真实 Key 当 Bearer 发到别处，
     * 这条链登录门挡不住（已登录的人照样能调这个接口）。
     */
    @Test
    void builtinEndpointCannotBeRepointed() {
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(engine("glm-4-flash", null)));

        AiEngine repointed = engine("glm-4-flash", null);
        repointed.setBaseUrl("https://attacker.example/v1/chat/completions");

        assertThatThrownBy(() -> registry.save(repointed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端点不可修改");
    }

    /** 删除自定义引擎要同时清掉 key.<id>：否则密钥永久留在磁盘，同名新引擎还会继承旧 Key。 */
    @Test
    void deletingCustomEngineClearsItsStoredKey() {
        AiEngine custom = engine("glm-4-flash", null);
        custom.setId("custom-abcd");
        custom.setBuiltin(false);
        when(repository.findById("custom-abcd")).thenReturn(Optional.of(custom));

        registry.delete("custom-abcd");

        verify(keyStore).save("custom-abcd", "");
        verify(repository).delete(custom);
    }

    /** 端点没变时必须照常保存型号/启停，否则面板上正常的操作也会被锁死。 */
    @Test
    void builtinStillSavesModelAndEnabledWhenEndpointUnchanged() {
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(engine("glm-4-flash", null)));
        when(repository.save(any(AiEngine.class))).thenAnswer(call -> call.getArgument(0));

        AiEngine incoming = engine("glm-4.7-flash", "glm-4-flash,glm-4.7-flash");
        incoming.setEnabled(false);

        AiEngine saved = registry.save(incoming);

        assertThat(saved.getBaseUrl()).isEqualTo("https://example.test/v1/chat/completions");
        assertThat(saved.getModel()).isEqualTo("glm-4.7-flash");
        assertThat(saved.isEnabled()).isFalse();
    }

    /** 欠费型号只取消勾选：候选清单要留着，用户充值后才能在面板上重新勾回来。 */
    @Test
    void quotaModelIsUncheckedButStaysInCatalog() {
        AiEngine record = engine("glm-4-flash", "glm-4-flash,glm-4.7-flash,glm-4.5-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));
        when(repository.save(any(AiEngine.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(registry.dropQuotaModel("glm-4-flash", "glm-4.7-flash")).isTrue();

        assertThat(record.getModelPool()).isEqualTo("glm-4-flash,glm-4.5-flash");
        assertThat(record.getModelsCsv()).contains("glm-4.7-flash");
        assertThat(record.isEnabled()).isTrue();
    }

    /** 摘掉主型号后主型号要跟着前移，否则 modelsOf 的第一个仍是脏值。 */
    @Test
    void droppingPrimaryPromotesTheNextCheckedModel() {
        AiEngine record = engine("glm-4.7-flash", "glm-4.7-flash,glm-4-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));
        when(repository.save(any(AiEngine.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(registry.dropQuotaModel("glm-4-flash", "glm-4.7-flash")).isTrue();

        assertThat(record.getModel()).isEqualTo("glm-4-flash");
        assertThat(registry.modelsOf("glm-4-flash")).containsExactly("glm-4-flash");
    }

    /** 最后一个型号也欠费时没有可退的型号了：停用引擎，而不是留一个必定失败的引擎继续撞。 */
    @Test
    void droppingLastModelDisablesEngine() {
        AiEngine record = engine("glm-4.7-flash", "glm-4.7-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));
        when(repository.save(any(AiEngine.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(registry.dropQuotaModel("glm-4-flash", "glm-4.7-flash")).isTrue();

        assertThat(record.getModelPool()).isEmpty();
        assertThat(record.isEnabled()).isFalse();
    }

    /** 池空 = 只用主型号，主型号欠费同样没有退路。 */
    @Test
    void quotaPrimaryInSingleModelModeDisablesEngine() {
        AiEngine record = engine("glm-4.7-flash", null);
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));
        when(repository.save(any(AiEngine.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(registry.dropQuotaModel("glm-4-flash", "glm-4.7-flash")).isTrue();

        assertThat(record.isEnabled()).isFalse();
    }

    /** 型号已经不在生效集里（例如连着报两次欠费）就不要再写库。 */
    @Test
    void droppingModelNotInPoolIsNoOp() {
        AiEngine record = engine("glm-4-flash", "glm-4-flash");
        when(repository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        assertThat(registry.dropQuotaModel("glm-4-flash", "glm-4.7-flash")).isFalse();

        verify(repository, never()).save(any(AiEngine.class));
    }
}
