package org.example.fqxs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 书源（Legado「阅读」）配置。
 *
 * <p>{@code /api/bs/**} 是给阅读 App 的只读端点，无法走浏览器登录门，因此有独立的
 * 可选令牌：留空表示不校验（内网自用）；公网暴露必须设置，否则等同于开放一个番茄代理。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "fqxs.book-source")
public class BookSourceConfig {

    /** 访问令牌，空串表示不校验；支持 {@code ?token=} 或 {@code X-Bs-Token} 头。 */
    private String token = "";

    /**
     * 书源请求与上游之间的间隔。比 AI 判定的 800ms 短——阅读端翻章要即时感；
     * 二者共享同一个全局槽位，不会同时打上游。
     */
    private long requestIntervalMillis = 300;

    /** 搜索每页条数上限：Legado 的 page 从 1 起，size 由本端钳制。 */
    private int searchSizeMax = 50;
}
