package org.example.fqxs.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正文 PUA 还原的契约：映射表随包发布，码位偏移必须与表顺序一致。
 */
class PuaDecoderTest {

    private final PuaDecoder puaDecoder = new PuaDecoder(new ObjectMapper());

    /** 映射表第 0 项对应 U+E3E8，值是 "D"：偏移错一位就会把所有正文解成乱码。 */
    @Test
    void firstCodePointMapsToFirstTableEntry() {
        assertThat(puaDecoder.isLoaded()).isTrue();
        assertThat(puaDecoder.decode(String.valueOf((char) 0xE3E8))).isEqualTo("D");
    }

    /** 未混淆的普通字符必须原样通过，否则正文里的标点与数字会被吃掉。 */
    @Test
    void plainTextPassesThroughUnchanged() {
        assertThat(puaDecoder.decode("你好，世界 2026")).isEqualTo("你好，世界 2026");
    }

    @Test
    void emptyAndNullInputsAreSafe() {
        assertThat(puaDecoder.decode("")).isEmpty();
        assertThat(puaDecoder.decode(null)).isNull();
    }

    /** 表外私用区字符保留原样并计数：这是“映射表过期”的唯一可观察信号。 */
    @Test
    void unmappedPrivateUseCharIsCountedAndKept() {
        String decoded = puaDecoder.decode(String.valueOf((char) 0xE000));

        assertThat(decoded).isEqualTo(String.valueOf((char) 0xE000));
        assertThat(puaDecoder.getUnresolvedCount()).isEqualTo(1);
    }

    /** 混排文本要逐字符判断：只解私用区，不能把后面的中文一起改写。 */
    @Test
    void mixedContentDecodesOnlyPrivateUseChars() {
        String raw = String.valueOf((char) 0xE3E8) + "章节";

        assertThat(puaDecoder.decode(raw)).isEqualTo("D章节");
        assertThat(puaDecoder.getUnresolvedCount()).isZero();
    }

    /** 幂等判断的入口：入库与历史回填都靠它决定是否值得走一次解码建串。 */
    @Test
    void containsPuaDetectsOnlyPrivateUseRange() {
        assertThat(PuaDecoder.containsPua(String.valueOf((char) 0xE3E8))).isTrue();
        // 表里没有的私用区也算：回填要能看见它，否则会漏行
        assertThat(PuaDecoder.containsPua(String.valueOf((char) 0xF8FF))).isTrue();
        assertThat(PuaDecoder.containsPua("这个游戏不对劲")).isFalse();
        assertThat(PuaDecoder.containsPua("")).isFalse();
        assertThat(PuaDecoder.containsPua(null)).isFalse();
    }

    /** 干净文本必须原样返回同一个引用，避免每次入库都为无变化的字段建新串。 */
    @Test
    void decodeIfNeededSkipsCleanText() {
        String clean = "剑来";

        assertThat(puaDecoder.decodeIfNeeded(clean)).isSameAs(clean);
        assertThat(puaDecoder.decodeIfNeeded(String.valueOf((char) 0xE3E8) + "章节")).isEqualTo("D章节");
        assertThat(puaDecoder.decodeIfNeeded(null)).isNull();
    }
}
