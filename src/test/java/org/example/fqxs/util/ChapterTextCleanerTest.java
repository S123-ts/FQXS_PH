package org.example.fqxs.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节清洗契约：六条规则各自可验证、整体幂等、不误删正文。
 */
class ChapterTextCleanerTest {

    private ChapterTextCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new ChapterTextCleaner(new PuaDecoder(new ObjectMapper()));
    }

    @Test
    void decodesPrivateUseCharsAndCountsResidue() {
        // 0xE3E8 在映射表首项，解得 "D"；0xE000 在表外，只能留着并计入残留
        String raw = String.valueOf((char) 0xE3E8) + "正文" + (char) 0xE000;

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, null);

        assertThat(cleaned.text()).startsWith("D正文");
        assertThat(cleaned.puaUnresolved()).isEqualTo(1);
        assertThat(cleaned.chars()).isEqualTo(cleaned.text().length());
    }

    @Test
    void dropsFirstLineWhenItRepeatsTheChapterTitle() {
        String raw = "第1章 开始\n\n正文第一段\n正文第二段";

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, "第1章 开始");

        assertThat(cleaned.text()).isEqualTo("正文第一段\n正文第二段");
    }

    @Test
    void titleComparisonIgnoresSpacesAndFullWidthSpaces() {
        String raw = "第 1 章　开始\n正文";

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, "第1章 开始");

        assertThat(cleaned.text()).isEqualTo("正文");
    }

    @Test
    void removesTrailingSpacesIncludingFullWidth() {
        // 第三方通道每段末尾多一个全角空格
        String raw = "第一段\u3000\u3000\n第二段 \n";

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, null);

        assertThat(cleaned.text()).isEqualTo("第一段\n第二段");
    }

    @Test
    void collapsesConsecutiveBlankLines() {
        String raw = "第一段\n\n\n\n第二段\n\n\n第三段";

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, null);

        assertThat(cleaned.text()).isEqualTo("第一段\n\n第二段\n\n第三段");
    }

    @Test
    void dropsAdLinesOnlyWhenTheyOccupyAWholeLine() {
        String raw = "第一段\n（本章完）\n请收藏本站\n第二段";

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, null);

        assertThat(cleaned.text()).isEqualTo("第一段\n第二段");
    }

    @Test
    void keepsInlineAdPhrasesInsideASentence() {
        // 广告句出现在正文中间时不动：少删一句广告，远好过删掉半句正文
        String raw = "他说本章未完，点击下一页继续阅读这句话很奇怪。";

        ChapterTextCleaner.Cleaned cleaned = cleaner.clean(raw, null);

        assertThat(cleaned.text()).isEqualTo(raw);
    }

    @Test
    void cleaningIsIdempotent() {
        String raw = "第1章 开始\n\n\n第一段\u3000\n（本章完）\n第二段";

        ChapterTextCleaner.Cleaned once = cleaner.clean(raw, "第1章 开始");
        ChapterTextCleaner.Cleaned twice = cleaner.clean(once.text(), "第1章 开始");

        assertThat(twice.text()).isEqualTo(once.text());
        assertThat(twice.chars()).isEqualTo(once.chars());
    }

    @Test
    void blankInputYieldsEmptyResult() {
        assertThat(cleaner.clean(null, "标题").isEmpty()).isTrue();
        assertThat(cleaner.clean("   \n\n ", "标题").isEmpty()).isTrue();
    }
}
