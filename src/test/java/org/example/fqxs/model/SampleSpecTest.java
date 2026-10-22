package org.example.fqxs.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取样规则的归一化契约：非法输入不报错（入口参数会把整批初筛卡死）；
 * 三档新规则（head / recent / random）章数固定、忽略调用方传的 sampleCount；
 * 历史档位（mid / mid_recent）仍按传入章数并钳到 [1, MAX_COUNT]。
 */
class SampleSpecTest {

    @Test
    void blankOrUnknownStrategyFallsBackToDefault() {
        assertThat(SampleSpec.of(null, 5).strategy()).isEqualTo(SampleSpec.HEAD);
        assertThat(SampleSpec.of("", 5).strategy()).isEqualTo(SampleSpec.HEAD);
        assertThat(SampleSpec.of("  ", 5).strategy()).isEqualTo(SampleSpec.HEAD);
        assertThat(SampleSpec.of("tail", 5).strategy()).isEqualTo(SampleSpec.HEAD);
        // 未传策略一律取默认 10 章
        assertThat(SampleSpec.of(null, 5).count()).isEqualTo(SampleSpec.DEFAULT_COUNT);
        assertThat(SampleSpec.of("tail", 17).count()).isEqualTo(SampleSpec.DEFAULT_COUNT);
    }

    @Test
    void explicitStrategiesArePreserved() {
        assertThat(SampleSpec.of("head", 3).strategy()).isEqualTo(SampleSpec.HEAD);
        assertThat(SampleSpec.of("mid", 3).strategy()).isEqualTo(SampleSpec.MID);
        assertThat(SampleSpec.of("recent", 3).strategy()).isEqualTo(SampleSpec.RECENT);
        assertThat(SampleSpec.of("random", 3).strategy()).isEqualTo(SampleSpec.RANDOM);
        assertThat(SampleSpec.of("mid_recent", 3).strategy()).isEqualTo(SampleSpec.MID_RECENT);
    }

    @Test
    void newRulesIgnoreIncomingCount() {
        // 三档是固定配方：残留的 sampleCount 不能改变章数
        assertThat(SampleSpec.of("head", 17).count()).isEqualTo(10);
        assertThat(SampleSpec.of("head", 1).count()).isEqualTo(10);
        assertThat(SampleSpec.of("recent", 2).count()).isEqualTo(10);
        // 随机档的 count 是"每段章数"，段数固定 2
        assertThat(SampleSpec.of("random", 999).count()).isEqualTo(5);
        assertThat(SampleSpec.of("random", 0).totalChapters()).isEqualTo(10);
    }

    @Test
    void legacyStrategiesStillClampCount() {
        assertThat(SampleSpec.of("mid", -1).count()).isEqualTo(10);
        assertThat(SampleSpec.of("mid", 3).count()).isEqualTo(3);
        assertThat(SampleSpec.of("mid_recent", 999).count()).isEqualTo(SampleSpec.MAX_COUNT);
    }

    @Test
    void defaultStrategyIgnoresCount() {
        SampleSpec spec = SampleSpec.of(null, 12);
        assertThat(spec.count()).isEqualTo(SampleSpec.DEFAULT_COUNT);
        assertThat(spec.isDefault()).isTrue();
    }

    @Test
    void headIsAlwaysDefaultAfterNormalization() {
        // 归一化后 head 的章数恒为 10，所以任何 head 都等价于默认档
        assertThat(SampleSpec.of("head", 4).isDefault()).isTrue();
        assertThat(SampleSpec.of("head", 10).isDefault()).isTrue();
        assertThat(SampleSpec.defaultSpec().isDefault()).isTrue();
        assertThat(SampleSpec.of("recent", 10).isDefault()).isFalse();
    }

    @Test
    void thirdPartyRulesAreMarked() {
        assertThat(SampleSpec.of("head", 0).usesThirdParty()).isFalse();
        assertThat(SampleSpec.of("recent", 0).usesThirdParty()).isTrue();
        assertThat(SampleSpec.of("random", 0).usesThirdParty()).isTrue();
    }

    @Test
    void modeNameCarriesSegmentsForRandom() {
        assertThat(SampleSpec.of("head", 0).modeName()).isEqualTo("head");
        assertThat(SampleSpec.of("recent", 0).modeName()).isEqualTo("recent");
        assertThat(SampleSpec.of("random", 0).modeName()).isEqualTo("random2x5");
    }
}
