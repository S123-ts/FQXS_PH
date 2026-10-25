package org.example.fqxs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时任务，目前只服务于「每天凌晨 1 点重拉全部榜单」。
 *
 * <p>放在独立配置类而不是启动类上，是为了让「本项目有调度能力」只出现在一处；
 * 后续新增任务直接写 {@code @Scheduled} 即可，不必再动启动类。</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
