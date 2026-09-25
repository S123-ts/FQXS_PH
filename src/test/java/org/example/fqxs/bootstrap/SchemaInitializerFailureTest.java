package org.example.fqxs.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

/**
 * 连接打不开（H2 文件被锁、路径损坏）时建表失败只能记日志，
 * 不能炸掉启动——HANDOVER §3 的“数据库不可用照样启动”契约。
 */
@ExtendWith(MockitoExtension.class)
class SchemaInitializerFailureTest {

    @Mock
    private DataSource dataSource;

    @Test
    void unreachableDatabaseDoesNotAbortBoot() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("file locked"));

        assertDoesNotThrow(() -> new SchemaInitializer(dataSource).run(null));
    }
}
