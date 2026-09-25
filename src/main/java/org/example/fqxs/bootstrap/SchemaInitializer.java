package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

/**
 * 建表脚本（db/schema-h2.sql，幂等）在启动时执行。不用 spring.sql.init：
 * 它的 continue-on-error 只吞逐条语句的失败，连接本身打不开时（H2 文件被锁、
 * 路径损坏）会在 context 初始化期炸掉整个应用，击穿“数据库不可用照样启动、
 * 读接口 503”的契约（HANDOVER §3）。这里用 JDBC 直接执行并整体 try/catch，
 * 失败只记日志。必须先于 StartupDataInitializer 执行（回填/播种依赖表已存在）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SchemaInitializer implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/schema-h2.sql"), StandardCharsets.UTF_8));
            log.info("建表脚本执行完成");
        } catch (Exception e) {
            log.error("建表脚本执行失败（数据库不可用？），应用将以 503 降级运行: {}", e.getMessage());
        }
    }
}
