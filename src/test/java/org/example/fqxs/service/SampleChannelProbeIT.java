package org.example.fqxs.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.client.ThirdPartyCatalogClient;
import org.example.fqxs.client.ThirdPartyChapterClient;
import org.example.fqxs.client.ThirdPartySources;
import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.model.SampleSpec;
import org.example.fqxs.repository.NovelChapterRepository;
import org.example.fqxs.util.ChapterTextCleaner;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手动探针：真实上游下，三档取样各自落在哪几章。
 *
 * <p>跑法（真连番茄与第三方云手机，别放进常规构建）：
 * {@code ./mvnw -B -o -Dtest=SampleChannelProbeIT -Dsample.channel.probe=true -DbookId=xxx test}</p>
 *
 * <p>之所以不起服务：H2 文件库被已在跑的实例独占（{@code data/fqxs.mv.db} 会报 file is locked），
 * 而本轮改动全在取样链路内，直接组装对象就能覆盖真实网络路径。章节仓储用内存 mock，
 * 本探针只验证"抽到哪几章、走哪一路"，不验证落库。</p>
 */
@EnabledIfSystemProperty(named = "sample.channel.probe", matches = "true")
class SampleChannelProbeIT {

    @Test
    void eachRuleLandsOnItsOwnChapterRange() {
        String bookId = System.getProperty("bookId", "");
        ObjectMapper mapper = new ObjectMapper();
        OkHttpClient http = new OkHttpClient();

        AiDetectConfig config = new AiDetectConfig();
        PuaDecoder decoder = new PuaDecoder(mapper);
        ChapterClient official = new ChapterClient(http, mapper, new FanqieConfig(), config, decoder);
        ThirdPartyChapterClient thirdParty =
                new ThirdPartyChapterClient(mapper, decoder, config, new ThirdPartySources(http, config));
        NovelChapterRepository repository = Mockito.mock(NovelChapterRepository.class);
        Mockito.when(repository.findByItemId(Mockito.anyString())).thenReturn(Optional.empty());
        ChapterContentService chapterStore = new ChapterContentService(repository, official,
                new ChapterTextCleaner(decoder), config);
        AiSampleCollector collector = new AiSampleCollector(directoryService(mapper, official, http, config),
                thirdParty, chapterStore, config, new UpstreamThrottle());

        AiSampleCollector.CollectedSample head = collector.collect(bookId, SampleSpec.of("head", 0));
        System.out.println("默认档 → 章节=[" + chapters(head.text()) + "] 字数=" + head.text().length()
                + " 档位=" + head.mode() + " 跨度=" + head.span());

        AiSampleCollector.CollectedSample recent = collector.collect(bookId, SampleSpec.of("recent", 0));
        System.out.println("最新档 → 章节=[" + chapters(recent.text()) + "] 字数=" + recent.text().length()
                + " 档位=" + recent.mode() + " 跨度=" + recent.span()
                + " 通道可用=" + thirdParty.isUsable());

        AiSampleCollector.CollectedSample random = collector.collect(bookId, SampleSpec.of("random", 0));
        System.out.println("随机档 → 章节=[" + chapters(random.text()) + "] 字数=" + random.text().length()
                + " 档位=" + random.mode() + " 跨度=" + random.span());

        assertTrue(head.text().length() >= config.getMinSampleChars(), "默认档样本要够判定字数");
        assertTrue(recent.text().length() >= config.getMinSampleChars(), "最新档样本要够判定字数");
        assertTrue(random.text().length() >= config.getMinSampleChars(), "随机档样本要够判定字数");

        // 通道关闭时最新档必须直接失败，不许偷偷降级成前 10 章
        AiDetectConfig offline = new AiDetectConfig();
        offline.setThirdPartyEnabled(false);
        ThirdPartyChapterClient disabled = new ThirdPartyChapterClient(mapper, new PuaDecoder(mapper),
                offline, new ThirdPartySources(http, offline));
        AiSampleCollector offlineCollector = new AiSampleCollector(directoryService(mapper, official, http, offline),
                disabled, chapterStore, offline, new UpstreamThrottle());
        assertThrows(CrawlException.class,
                () -> offlineCollector.collect(bookId, SampleSpec.of("recent", 0)),
                "通道关闭时最新档应报错而不是降级");
    }

    /** 目录取数（官方优先、失败回退第三方）：探针按当前配置现组一条链路。 */
    private ChapterDirectoryService directoryService(ObjectMapper mapper, ChapterClient official,
                                                     OkHttpClient http, AiDetectConfig config) {
        return new ChapterDirectoryService(official,
                new ThirdPartyCatalogClient(mapper, new ThirdPartySources(http, config)),
                new UpstreamThrottle());
    }

    /** 从样本里抽回章号：取样器写入的是「【第N章】」标记。 */
    private String chapters(String sample) {
        Matcher matcher = Pattern.compile("【第(\\d+)章】").matcher(sample);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            builder.append(matcher.group(1)).append(' ');
        }
        return builder.toString().trim();
    }
}
