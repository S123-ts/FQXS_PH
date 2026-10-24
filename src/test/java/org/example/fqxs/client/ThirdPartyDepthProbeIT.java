package org.example.fqxs.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

/**
 * 手动探针：同一批章节，官方 SSR 与第三方正文通道各能拿到多少字。
 *
 * <p>跑法（真连上游，别放进常规构建）：
 * {@code ./mvnw -B -o -Dtest=ThirdPartyDepthProbeIT -Dthird.party.probe=true -DbookId=xxx test}</p>
 *
 * <p>判读：免费区（前 10 章）两路都该是几千字；可读区外官方只剩 150–180 字试读，
 * 第三方仍应是几千字。若第三方在深章也只剩几百字或 usable=false，说明那台共享云手机又断了，
 * 取样会自动退回「只用可读区」的降级形态。</p>
 */
@EnabledIfSystemProperty(named = "third.party.probe", matches = "true")
class ThirdPartyDepthProbeIT {

    /** 抽查位置：免费区 3 个 + 会员区 3 个，覆盖降级边界两侧。 */
    private static final int[] PROBE_INDEXES = {0, 4, 9, 10, 24, 99, 199};

    @Test
    void comparesChapterDepthAcrossBothChannels() throws InterruptedException {
        String bookId = System.getProperty("bookId", "");
        ObjectMapper mapper = new ObjectMapper();
        AiDetectConfig config = new AiDetectConfig();
        PuaDecoder decoder = new PuaDecoder(mapper);
        OkHttpClient http = new OkHttpClient();
        ChapterClient official = new ChapterClient(http, mapper, new FanqieConfig(), config, decoder);
        ThirdPartyChapterClient thirdParty =
                new ThirdPartyChapterClient(mapper, decoder, config, new ThirdPartySources(http, config));

        List<String> ids = official.listChapterIds(bookId);
        System.out.println("TOTAL=" + ids.size() + " 可读区上限=" + config.getFreeHeadChapters());

        for (int index : PROBE_INDEXES) {
            if (index >= ids.size()) {
                continue;
            }
            String itemId = ids.get(index);
            int officialChars = lengthOf(() -> official.fetchChapter(itemId));
            int thirdChars = lengthOf(() -> thirdParty.fetchChapter(itemId));
            System.out.printf("CH%-4d official=%-6d third=%-6d usable=%s%n",
                    index + 1, officialChars, thirdChars, thirdParty.isUsable());
            Thread.sleep(config.getRequestIntervalMillis());
        }
    }

    /** 抓取失败不中断探针：把失败也量化成 0 字并打印原因，方便一眼看出是哪一路挂了。 */
    private int lengthOf(java.util.function.Supplier<ChapterSample> fetcher) {
        try {
            return fetcher.get().text().length();
        } catch (RuntimeException exception) {
            System.out.println("      抓取失败: " + exception.getMessage());
            return -1;
        }
    }
}
