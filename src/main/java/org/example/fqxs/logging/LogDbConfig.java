package org.example.fqxs.logging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 日志落库配置（{@code app.log-db.*}）。
 *
 * <p>三类日志各有独立开关：关掉某一类只停它的写入，队列照常排空（不会因为没人消费而堆满）。
 * 总开关 {@code enabled=false} 时整个写入线程不启动，日志只留控制台与文件。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.log-db")
public class LogDbConfig {

    /** 总开关：关闭后不再落库，控制台与文件日志不受影响。 */
    private boolean enabled = true;

    /** 程序全量日志（{@code app_log}）是否落库。 */
    private boolean appEnabled = true;

    /** API 日志（{@code api_log}）是否落库。 */
    private boolean apiEnabled = true;

    /** 第三方抽取日志（{@code third_party_log}）是否落库。 */
    private boolean thirdPartyEnabled = true;

    /** 程序日志保留天数：全量日志体量最大，默认只留一周。 */
    private int appKeepDays = 7;

    /** API 日志保留天数：访问轨迹比程序日志更有长期价值。 */
    private int apiKeepDays = 30;

    /** 第三方抽取日志保留天数：线路健康度按周看就够，留 30 天能看出周期性。 */
    private int thirdPartyKeepDays = 30;

    /** 清理任务 cron（六段，东八区）。默认 03:30，错开凌晨 01:00 的全量拉榜。 */
    private String retentionCron = "0 30 3 * * *";

    /** 查询接口单次最多返回多少行，防止一次把整表拉出来。 */
    private int queryMaxRows = 500;
}
