package org.example.fqxs.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 番茄正文的私用区（PUA）字符还原器。
 *
 * <p>上游把常用汉字重映射到 U+E3E8–U+E55B，直接取回的正文不可读，AI 判定与搜索都会失效。
 * 映射表来自开源项目 fanqienovel-downloader 的 charset.json：两套数组分别对应
 * 起始码位 U+E3E8（372 项）与 U+E3E9（371 项，偏移 +1 的备用编码）。</p>
 *
 * <p>映射表加载失败不让应用启动失败——此时解码退化为原样返回，并在日志里留痕。</p>
 */
@Slf4j
@Component
public class PuaDecoder {

    /** 资源随包发布，避免运行时依赖外部文件。 */
    private static final String CHARSET_RESOURCE = "/pua-charset.json";

    /** 两套编码的起始码位，与 charset.json 的行顺序一一对应。 */
    private static final int[] TABLE_BASE_CODE_POINTS = {0xE3E8, 0xE3E9};

    private static final char PUA_RANGE_START = '\uE000';
    private static final char PUA_RANGE_END = '\uF8FF';

    /** 表里用 "?" 表示该码位没有可靠映射，遇到时保留原字符，避免凭空造出错字。 */
    private static final String UNMAPPED_MARK = "?";

    private final List<List<String>> mappingTables;

    /** 未命中映射的私用区字符数，供排查“映射表是否过期”，不参与判定。 */
    private int unresolvedCount;

    public PuaDecoder(ObjectMapper objectMapper) {
        this.mappingTables = loadTables(objectMapper);
    }

    /**
     * 把 PUA 混淆正文还原成可读文本。
     *
     * @param rawText 上游返回的原始正文（可能含未混淆的普通字符）
     * @return 还原后的文本；映射表未加载时原样返回
     */
    public String decode(String rawText) {
        if (rawText == null || rawText.isEmpty() || mappingTables.isEmpty()) {
            return rawText;
        }
        unresolvedCount = 0;
        StringBuilder decodedText = new StringBuilder(rawText.length());
        for (int index = 0; index < rawText.length(); index++) {
            char originChar = rawText.charAt(index);
            String mappedChar = mapChar(originChar);
            if (mappedChar != null) {
                decodedText.append(mappedChar);
            } else {
                if (originChar >= PUA_RANGE_START && originChar <= PUA_RANGE_END) {
                    unresolvedCount++;
                }
                decodedText.append(originChar);
            }
        }
        return decodedText.toString();
    }

    /**
     * 文本里是否还留着私用区字符。
     *
     * <p>入库与历史回填都靠它做幂等判断：已经还原过的文本再解一次结果不变，
     * 但没必要为每个字段都走一遍建串；干净文本在这里就短路返回。</p>
     *
     * @param text 待检查文本，可为 null
     * @return 含至少一个 U+E000–U+F8FF 字符时为 true
     */
    public static boolean containsPua(CharSequence text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= PUA_RANGE_START && current <= PUA_RANGE_END) {
                return true;
            }
        }
        return false;
    }

    /**
     * 数一段文本里还剩多少个私用区字符。
     *
     * <p>与 {@link #getUnresolvedCount()} 的区别：那个是实例状态，最后一次 decode 的结果，
     * 批量初筛并发调用时会互相覆盖；这里是纯函数，章节入库统计残留量用它。</p>
     *
     * @param text 已解码文本，可为 null
     * @return 落在 U+E000–U+F8FF 的字符个数
     */
    public static int countPua(CharSequence text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current >= PUA_RANGE_START && current <= PUA_RANGE_END) {
                count++;
            }
        }
        return count;
    }

    /**
     * 只在含私用区字符时解码，否则原样返回。
     *
     * <p>书名、作者、简介这类字段绝大多数是干净文本，先判断再解码比无条件解码省一次建串。</p>
     *
     * @param rawText 上游文本，可为 null
     * @return 还原后的文本；本来就没有混淆字符时返回原引用
     */
    public String decodeIfNeeded(String rawText) {
        return containsPua(rawText) ? decode(rawText) : rawText;
    }

    /** 映射表是否可用；不可用时调用方应把抓取结果按失败处理，而不是送去判定。 */
    public boolean isLoaded() {
        return !mappingTables.isEmpty();
    }

    /** 最近一次 {@link #decode(String)} 中未能还原的私用区字符数。 */
    public int getUnresolvedCount() {
        return unresolvedCount;
    }

    /**
     * 按两套表查找单个字符的明文。
     *
     * @param originChar 原始字符
     * @return 明文字符串；不属于任何映射区间或映射不可靠时返回 null
     */
    private String mapChar(char originChar) {
        int codePoint = originChar;
        for (int tableIndex = 0; tableIndex < mappingTables.size(); tableIndex++) {
            int baseCodePoint = TABLE_BASE_CODE_POINTS[tableIndex];
            List<String> mappingTable = mappingTables.get(tableIndex);
            int offset = codePoint - baseCodePoint;
            if (offset < 0 || offset >= mappingTable.size()) {
                continue;
            }
            String mappedChar = mappingTable.get(offset);
            if (mappedChar == null || mappedChar.isEmpty() || UNMAPPED_MARK.equals(mappedChar)) {
                return null;
            }
            return mappedChar;
        }
        return null;
    }

    /**
     * 读取并解析映射表；任何异常都降级为“无表”，只记日志。
     *
     * @param objectMapper JSON 解析器
     * @return 两套映射表；加载失败返回空列表
     */
    private List<List<String>> loadTables(ObjectMapper objectMapper) {
        try (InputStream inputStream = PuaDecoder.class.getResourceAsStream(CHARSET_RESOURCE)) {
            if (inputStream == null) {
                log.warn("未找到字符映射表 {}，正文将按原样返回（可能出现乱码）", CHARSET_RESOURCE);
                return List.of();
            }
            List<List<String>> tables = objectMapper.readValue(inputStream,
                    new TypeReference<List<List<String>>>() {
                    });
            log.info("PUA 字符映射表加载完成: {} 套, 条数 {}", tables.size(),
                    tables.stream().mapToInt(List::size).sum());
            return tables;
        } catch (Exception exception) {
            log.warn("字符映射表加载失败，正文将按原样返回: {}", exception.getMessage(), exception);
            return List.of();
        }
    }
}
