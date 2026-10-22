package org.example.fqxs.util;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 章节正文清洗器：把上游抓回来的正文变成「可以直接送判、也可以直接入库」的形态。
 *
 * <p>六条规则，全部幂等（同一段文本跑两遍结果相同）：</p>
 * <ol>
 *   <li>PUA 还原 + 残留计数：解码已由 {@code ChapterClient} 做过，这里再走一次
 *       {@link PuaDecoder#decodeIfNeeded} 是幂等的兜底——干净文本直接返回原引用；</li>
 *   <li>去与章节标题重复的首行：上游常在正文开头再写一遍「第 1 章 xxx」；</li>
 *   <li>去整行广告/分页尾注：<b>只删整行完全匹配</b>的固定短句，不做全文替换，
 *       避免把正文里出现的同名词删掉；</li>
 *   <li>去行尾空白：第三方通道每段末尾多一个全角空格，不去掉会让提示词里全是隐形空隙；</li>
 *   <li>连续空行归一：3 个以上换行压成一个空行；</li>
 *   <li>整体 trim。</li>
 * </ol>
 *
 * <p>故意<b>不做</b>的事：不去 HTML 实体、不去标点、不合并段落、不截断字数。
 * 前两件 {@code ChapterClient.htmlToPlainText} 已经做过，重复做会改坏正文里的 {@code &}；
 * 截断交给调用方（{@code max-chars-per-book}），清洗器不替它做决定。</p>
 */
@Component
@RequiredArgsConstructor
public class ChapterTextCleaner {

    /**
     * 清洗结果。
     *
     * @param text          清洗后正文
     * @param chars         正文字数（含标点，与 {@code ChapterSample.chars} 同口径）
     * @param puaUnresolved 解码后仍残留的私用区字符数，正常为 0
     */
    public record Cleaned(String text, int chars, int puaUnresolved) {

        /** 清洗后没有可用正文。 */
        public boolean isEmpty() {
            return text == null || text.isEmpty();
        }
    }

    /** 行尾空白：半角空格、制表符、全角空格（第三方通道的典型特征）。 */
    private static final java.util.regex.Pattern TRAILING_BLANKS =
            java.util.regex.Pattern.compile("[ \\t\\u3000]+$", java.util.regex.Pattern.MULTILINE);

    /** 三个以上连续换行 = 两个以上空行，压成一个空行。 */
    private static final java.util.regex.Pattern BLANK_RUN =
            java.util.regex.Pattern.compile("\\n{3,}");

    /**
     * 整行广告/尾注白名单。
     *
     * <p>只收「独占一行且完全等于」的短句。像「番茄小说」这种既可能是站名也可能出现在正文里的词
     * 刻意不收——少删一句广告的代价远小于删掉半句正文。</p>
     */
    private static final Set<String> AD_LINES = Set.of(
            "本章未完，点击下一页继续阅读",
            "本章未完,点击下一页继续阅读",
            "本章未完，请点击下一页继续阅读",
            "（本章完）",
            "(本章完)",
            "请收藏本站",
            "请收藏本站！",
            "请记住本站域名"
    );

    private final PuaDecoder puaDecoder;

    /**
     * 清洗一章正文。
     *
     * @param rawText      上游正文（可能含 PUA、HTML 残留空白、广告行）
     * @param chapterTitle 章节标题，用于识别正文开头重复的那一行；可为空
     * @return 清洗结果，永不为 null
     */
    public Cleaned clean(String rawText, String chapterTitle) {
        if (rawText == null || rawText.isBlank()) {
            return new Cleaned("", 0, 0);
        }
        String text = puaDecoder.decodeIfNeeded(rawText);
        int unresolved = PuaDecoder.countPua(text);
        // 先统一换行，后面的正则与按行处理才只需要考虑 \n
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = dropRepeatedTitle(text, chapterTitle);
        text = dropAdLines(text);
        text = TRAILING_BLANKS.matcher(text).replaceAll("");
        text = BLANK_RUN.matcher(text).replaceAll("\n\n");
        text = text.trim();
        return new Cleaned(text, text.length(), unresolved);
    }

    /**
     * 首行与章节标题重复时删掉首行。
     *
     * <p>比较时去掉所有空白与全角空格：上游标题常带「第1章」与「第 1 章」这类差异。</p>
     */
    private String dropRepeatedTitle(String text, String chapterTitle) {
        if (chapterTitle == null || chapterTitle.isBlank()) {
            return text;
        }
        int lineEnd = text.indexOf('\n');
        String firstLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
        if (!compact(firstLine).equals(compact(chapterTitle))) {
            return text;
        }
        String rest = lineEnd < 0 ? "" : text.substring(lineEnd + 1);
        // 标题行后面通常还跟着一个空行，一并吃掉，避免清洗后开头多一空行
        return rest.startsWith("\n") ? rest.substring(1) : rest;
    }

    /** 去掉所有空白字符，用于标题等值比较。 */
    private String compact(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder compacted = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != ' ' && current != '\t' && current != '\u3000' && current != '\n') {
                compacted.append(current);
            }
        }
        return compacted.toString();
    }

    /** 删掉整行完全匹配白名单的行，其余原样保留（含行顺序与空行）。 */
    private String dropAdLines(String text) {
        if (text.indexOf('\n') < 0) {
            return AD_LINES.contains(text.trim()) ? "" : text;
        }
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!AD_LINES.contains(line.trim())) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }
}
