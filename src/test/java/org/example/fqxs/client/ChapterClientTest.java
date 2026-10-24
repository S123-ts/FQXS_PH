package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.model.ChapterRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目录结构解析：书源要标题与卷名，AI 取样只要 itemId，两边共用这一份结果。 */
class ChapterClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesGroupedChaptersWithVolumesAndLocks() throws Exception {
        JsonNode grouped = mapper.readTree("""
                [
                  [{"itemId":"1","title":"第1章 开端","volume_name":"第一卷","isChapterLock":false,"needPay":0},
                   {"itemId":"2","title":"第2章 锁章","volume_name":"第一卷","isChapterLock":true,"needPay":0}],
                  [{"itemId":"3","title":"第3章 付费","volume_name":"第二卷","needPay":10}]
                ]
                """);

        List<ChapterRef> refs = ChapterClient.parseChapters(grouped);

        assertEquals(List.of("1", "2", "3"), refs.stream().map(ChapterRef::itemId).toList());
        assertEquals("第一卷", refs.get(0).volumeName());
        assertEquals("第1章 开端", refs.get(0).title());
        assertFalse(refs.get(0).locked());
        assertTrue(refs.get(1).locked(), "isChapterLock 应判定为锁章");
        assertTrue(refs.get(2).locked(), "needPay>0 也应判定为锁章");
        assertEquals("第二卷", refs.get(2).volumeName());
    }

    @Test
    void booleanNeedPayIsAlsoLocked() throws Exception {
        JsonNode grouped = mapper.readTree("[[{\"itemId\":\"1\",\"needPay\":true}]]");

        assertTrue(ChapterClient.parseChapters(grouped).get(0).locked(),
                "上游若把 needPay 改成布尔也要识别成锁章");
    }

    @Test
    void skipsChapterEntriesWithoutItemId() throws Exception {
        JsonNode grouped = mapper.readTree("""
                [[{"title":"没有 ID"},{"itemId":"ok","title":"有 ID"}]]
                """);

        assertEquals(List.of("ok"), ChapterClient.parseChapters(grouped).stream()
                .map(ChapterRef::itemId).toList());
    }

    /** 同一卷的章节必须共享同一个卷名实例：目录缓存按本持有，逐章各存一份会白占几十 MB。 */
    @Test
    void chaptersOfTheSameVolumeShareOneNameInstance() throws Exception {
        JsonNode grouped = mapper.readTree("""
                [[{"itemId":"1","title":"第1章","volume_name":"第一卷"},
                  {"itemId":"2","title":"第2章","volume_name":"第一卷"}],
                 [{"itemId":"3","title":"第3章","volume_name":"第二卷"}]]
                """);

        List<ChapterRef> refs = ChapterClient.parseChapters(grouped);

        assertEquals(refs.get(0).volumeName(), refs.get(1).volumeName());
        assertSame(refs.get(0).volumeName(), refs.get(1).volumeName(), "同卷应复用同一实例");
        assertNotSame(refs.get(0).volumeName(), refs.get(2).volumeName(), "不同卷不能混用");
    }

    /** 老响应只有扁平 ID 列表：标题留空，但顺序必须保住，否则阅读端会乱序翻章。 */
    @Test
    void flatItemIdsAreTheFallbackWithNoTitles() throws Exception {
        JsonNode ids = mapper.readTree("[\"a\",\"\",\"b\",123]");

        List<ChapterRef> refs = ChapterClient.parseFlatItemIds(ids);

        assertEquals(List.of("a", "b"), refs.stream().map(ChapterRef::itemId).toList());
        assertTrue(refs.stream().allMatch(ref -> ref.title().isEmpty() && !ref.locked()));
    }

    @Test
    void missingStructuresYieldEmptyListsInsteadOfThrowing() {
        assertTrue(ChapterClient.parseChapters(mapper.missingNode()).isEmpty());
        assertTrue(ChapterClient.parseChapters(null).isEmpty());
        assertTrue(ChapterClient.parseFlatItemIds(null).isEmpty());
    }
}
