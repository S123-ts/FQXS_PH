package org.example.fqxs.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 连接打不开（H2 文件被锁、路径损坏）时迁移失败只能记日志，
 * 不能炸掉启动——技术交接 §3 的“数据库不可用照样启动”契约。
 */
class FlywayToleranceTest {

    @Test
    void failingMigrationDoesNotAbortBoot() {
        Flyway flyway = mock(Flyway.class);
        doThrow(new RuntimeException("file locked")).when(flyway).migrate();

        FlywayMigrationStrategy strategy = new FlywayConfig().tolerantFlywayStrategy();

        assertDoesNotThrow(() -> strategy.migrate(flyway));
    }
}
