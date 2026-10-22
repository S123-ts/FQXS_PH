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
 * 手动探测：目录索引与正文是否对齐（重点验证第 100 章能真的抓到）。
 *
 * <p>运行：{@code ./mvnw -o -Dtest=ChapterDepthProbeIT -Dchapter.probe=true -DbookId=xxx test}</p>
 */
@EnabledIfSystemProperty(named = "chapter.probe", matches = "true")
class ChapterDepthProbeIT {

    @Test
    void fetchesDeepChapters() {
        String bookId = System.getProperty("bookId", "");
        ObjectMapper mapper = new ObjectMapper();
        ChapterClient client = new ChapterClient(new OkHttpClient(), mapper,
                new FanqieConfig(), new AiDetectConfig(), new PuaDecoder(mapper));

        List<String> ids = client.listChapterIds(bookId);
        System.out.println("TOTAL=" + ids.size());

        for (int index = 0; index < ids.size() && index < 220; index += 10) {
            String itemId = ids.get(index);
            try {
                ChapterSample sample = client.fetchChapter(itemId);
                String text = sample.text() == null ? "" : sample.text();
                System.out.printf("CH%-4d len=%-6d title=%s%n", index + 1, text.length(), sample.title());
            } catch (RuntimeException exception) {
                System.out.printf("CH%-4d FAILED: %s%n", index + 1, exception.getMessage());
            }
        }
    }
}
