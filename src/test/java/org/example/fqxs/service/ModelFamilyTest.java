package org.example.fqxs.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 家族归一规则的契约：上下文长度 / 日期快照 / 变体词是「同一模型的包装」，剥掉后必须归到一起；
 * 版本号与参数规模是「真正不同的模型」，绝不能合并。失败方向是少合并（多花额度），不是多合并（漏模型）。
 */
class ModelFamilyTest {

    /** 上下文长度只是包装：ernie-4.5-turbo 的 32k 与 128k 归到同一族。 */
    @Test
    void contextVariantsShareFamily() {
        assertEquals(ModelFamily.keyOf("ernie-4.5-turbo-32k"), ModelFamily.keyOf("ernie-4.5-turbo-128k"));
        assertEquals("ernie-4.5", ModelFamily.keyOf("ernie-4.5-turbo-32k"));
    }

    /** 用户点名的例子：glm-4 与 glm-4-flash 一个就行。 */
    @Test
    void variantWordSharesFamily() {
        assertEquals(ModelFamily.keyOf("glm-4"), ModelFamily.keyOf("glm-4-flash"));
    }

    /** 日期快照也是包装：glm-4-flash 与它的 250414 版归到一起。 */
    @Test
    void dateSnapshotSharesFamily() {
        assertEquals(ModelFamily.keyOf("glm-4-flash"), ModelFamily.keyOf("glm-4-flash-250414"));
        assertEquals("glm-4-9b", ModelFamily.keyOf("GLM-4-9B-0414"));
    }

    /** 版本号不能剥：glm-4 与 glm-5.3-flash 是两代模型，各跑各的。 */
    @Test
    void versionNumberKeepsFamiliesApart() {
        assertEquals("glm-4", ModelFamily.keyOf("glm-4-flash"));
        assertEquals("glm-5.3", ModelFamily.keyOf("glm-5.3-flash"));
    }

    /** 参数规模不能剥：Qwen2.5-7B 与 Qwen3-8B、GLM-4 与 GLM-4-9B 都不是同一模型。 */
    @Test
    void parameterSizeKeepsFamiliesApart() {
        assertEquals("qwen/qwen2.5-7b", ModelFamily.keyOf("Qwen/Qwen2.5-7B-Instruct"));
        assertEquals("qwen/qwen3-8b", ModelFamily.keyOf("Qwen/Qwen3-8B"));
        assertEquals("glm-4-9b", ModelFamily.keyOf("GLM-4-9B-0414"));
    }

    /** 内部行标识（model#verify）先切掉再归一，否则永远归不到一起。 */
    @Test
    void internalRowMarkerIsStripped() {
        assertEquals(ModelFamily.keyOf("glm-5.3-flash"), ModelFamily.keyOf("glm-5.3-flash#verify"));
    }

    /** 大小写不参与家族判定。 */
    @Test
    void caseInsensitive() {
        assertEquals(ModelFamily.keyOf("glm-4"), ModelFamily.keyOf("GLM-4-Flash"));
    }

    /** 去重保序，每族取池内最先出现的型号：代表型号不随哈希顺序漂移。 */
    @Test
    void distinctKeepsFirstOfEachFamilyInPoolOrder() {
        List<String> pool = List.of("ernie-4.5-turbo-32k", "ernie-4.5-turbo-128k", "GLM-4-9B-0414");

        assertEquals(List.of("ernie-4.5-turbo-32k", "GLM-4-9B-0414"), ModelFamily.distinct(pool));
    }

    /** 去重不改变单一型号与空白处理；结果永远非空（modelsOf 的返回契约）。 */
    @Test
    void distinctHandlesTrivialPools() {
        assertEquals(List.of("glm-5.3-flash"), ModelFamily.distinct(List.of("glm-5.3-flash")));
        assertEquals(List.of("glm-4"), ModelFamily.distinct(List.of("  ", "glm-4")));
        assertEquals(List.of(), ModelFamily.distinct(List.of()));
    }

    /** 空白与 null 不猜不抛：原样返回空键。 */
    @Test
    void keyOfToleratesBlankInput() {
        assertEquals("", ModelFamily.keyOf(null));
        assertEquals("", ModelFamily.keyOf("   "));
    }
}
