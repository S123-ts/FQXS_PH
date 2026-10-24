package org.example.fqxs.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.ChapterRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三方 {@code /catalog} 的解析契约（2026-10-24 实测《我不是戏神》1928 章的形态）。
 *
 * <p>只测解析，不连真实线路：{@code parseCatalog} 包级可见就是为这条边界服务的。</p>
 */
class ThirdPartyCatalogClientTest {

    private final ThirdPartyCatalogClient client =
            new ThirdPartyCatalogClient(new ObjectMapper(), null);

    /** 首选形态：{@code item_data_list} 每章带标题与卷名，可直接对齐 ChapterRef。 */
    @Test
    void parsesItemDataListWithVolumeAndLockFlag() {
        String body = """
                {"data":{"code":"0","data":{"item_data_list":[
                  {"item_id":"a","title":"第1章 戏鬼回家","volume_name":"第一卷：戏中人","need_unlock":false},
                  {"item_id":"b","title":"第2章 我们在看着你","volume_name":"第一卷：戏中人","need_unlock":true}
                ]}},"isSuccess":true}
                """;

        List<ChapterRef> chapters = client.parseCatalog(body, "42");

        assertEquals(2, chapters.size());
        assertEquals("a", chapters.get(0).itemId());
        assertEquals("第1章 戏鬼回家", chapters.get(0).title());
        assertEquals("第一卷：戏中人", chapters.get(0).volumeName());
        assertFalse(chapters.get(0).locked());
        assertTrue(chapters.get(1).locked(), "need_unlock 就是锁章标记");
    }

    /** 上游只给精简块时按 {@code catalog_data} 兜底，卷名留空但不丢章。 */
    @Test
    void fallsBackToCatalogDataWhenItemDataIsAbsent() {
        String body = """
                {"data":{"code":"0","data":{"catalog_data":[
                  {"catalog_id":"a","catalog_title":"第1章","item_id":"a","need_unlock":false}
                ]}}}
                """;

        List<ChapterRef> chapters = client.parseCatalog(body, "42");

        assertEquals(1, chapters.size());
        assertEquals("第1章", chapters.get(0).title());
        assertEquals("", chapters.get(0).volumeName());
    }

    /** 与正文通道同一口径：堆栈装进 data 时 isSuccess 仍可能为 true，只能按形态判失败。 */
    @Test
    void stackPayloadIsRejected() {
        String body = "{\"data\":\"java.lang.NullPointerException: onNext called with null\",\"isSuccess\":true}";

        assertThrows(CrawlException.class, () -> client.parseCatalog(body, "42"));
    }

    @Test
    void nonZeroCodeIsRejected() {
        assertThrows(CrawlException.class,
                () -> client.parseCatalog("{\"data\":{\"code\":\"500\",\"data\":{}}}", "42"));
    }

    /** 空目录不能当「这本书没章节」：它和线路故障长得一样，宁可报错让上层换源。 */
    @Test
    void emptyCatalogIsRejected() {
        String body = "{\"data\":{\"code\":\"0\",\"data\":{\"item_data_list\":[]}}}";

        CrawlException failure = assertThrows(CrawlException.class, () -> client.parseCatalog(body, "42"));
        assertTrue(failure.getMessage().contains("为空"));
    }

    /** 缺 item_id 的条目要丢掉，否则阅读端会拿到打不开的空链接。 */
    @Test
    void entriesWithoutItemIdAreSkipped() {
        String body = """
                {"data":{"code":"0","data":{"item_data_list":[
                  {"title":"没有 ID 的章","volume_name":"第一卷","need_unlock":false},
                  {"item_id":"a","title":"第1章","volume_name":"第一卷","need_unlock":false}
                ]}}}
                """;

        assertEquals(1, client.parseCatalog(body, "42").size());
    }
}
