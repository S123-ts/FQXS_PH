package org.example.fqxs.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.model.ChapterRef;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 真实对照探针：第三方 {@code /catalog} 与官方目录是不是同一份章节。
 *
 * <p>回退链路只有官方失败时才走，平时不会被真实触发，所以这里单独把第三方目录拉出来
 * 与官方逐章比一次：章数相同、首章标题相同、卷名非空，才算「这条回退路真的能用」。
 * 默认跳过，需要显式开开关。</p>
 *
 * <p>运行：{@code -Dtest=ThirdPartyCatalogProbeIT -Dthird.party.catalog.probe=true
 * -DbookId=7276384138653862966}</p>
 */
@EnabledIfSystemProperty(named = "third.party.catalog.probe", matches = "true")
class ThirdPartyCatalogProbeIT {

    @Test
    void thirdPartyCatalogMatchesTheOfficialOne() {
        String bookId = System.getProperty("bookId", "");
        ObjectMapper mapper = new ObjectMapper();
        AiDetectConfig config = new AiDetectConfig();
        OkHttpClient http = new OkHttpClient();
        PuaDecoder decoder = new PuaDecoder(mapper);

        ChapterClient official = new ChapterClient(http, mapper, new FanqieConfig(), config, decoder);
        ThirdPartyCatalogClient catalog =
                new ThirdPartyCatalogClient(mapper, new ThirdPartySources(http, config));

        List<ChapterRef> officialChapters = official.listChapters(bookId);
        List<ChapterRef> thirdChapters = catalog.listChapters(bookId);

        System.out.printf("TOTAL official=%d third=%d%n", officialChapters.size(), thirdChapters.size());
        System.out.printf("FIRST official=[%s] third=[%s]%n",
                officialChapters.get(0).title(), thirdChapters.get(0).title());
        System.out.printf("VOLUME official=[%s] third=[%s]%n",
                officialChapters.get(0).volumeName(), thirdChapters.get(0).volumeName());
        System.out.printf("LOCKED official=%d third=%d  末章 third=[%s]%n",
                officialChapters.stream().filter(ChapterRef::locked).count(),
                thirdChapters.stream().filter(ChapterRef::locked).count(),
                thirdChapters.get(thirdChapters.size() - 1).title());
        // 两个数天生不该相等（实测 1918 vs 0）：官方 needPay 是网页付费视角，
        // 第三方 need_unlock 是 App 会员视角。这里只打印不断言，避免把口径差异误判成回退坏了。

        assertEquals(officialChapters.size(), thirdChapters.size(), "两路章节数必须一致，否则回退会丢章");
        assertEquals(officialChapters.get(0).itemId(), thirdChapters.get(0).itemId(), "首章 ID 要对得上");
        assertEquals(officialChapters.get(0).title(), thirdChapters.get(0).title(), "标题口径要一致");
        assertFalse(thirdChapters.get(0).volumeName().isBlank(), "第三方要带得出卷名，阅读端才有分卷");
    }
}
