package org.example.fqxs.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 迁移失败不阻断启动：H2 文件被锁或路径损坏时，应用仍要启动并以 503 降级。
 * 迁移本身仍由 Flyway 管理，数据库恢复可用后的下一次启动会补跑。
 */
@Slf4j
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy tolerantFlywayStrategy() {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (Exception e) {
                log.error("Flyway 迁移失败（数据库不可用？），应用将以 503 降级运行: {}", e.getMessage());
            }
        };
    }
}
