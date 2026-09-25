package org.example.fqxs.bootstrap;

import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * “数据库不可用时应用照样能启动”是 HANDOVER §3 的契约，而 ApplicationRunner 抛异常
 * 会终止整个启动——所以 runner 必须吞掉初始化失败只记日志。这两个用例钉住两条失败路径：
 * 事务开启即失败（库连不上）与回填途中失败（磁盘/锁）。
 */
@ExtendWith(MockitoExtension.class)
class StartupDataInitializerFailureTest {

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private ConfigService configService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private StartupDataInitializer initializer() {
        return new StartupDataInitializer(novelRepository, configService, new TransactionTemplate(transactionManager));
    }

    @Test
    void databaseUnavailableAtStartupDoesNotAbortBoot() {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new IllegalStateException("Cannot open connection"));

        assertDoesNotThrow(() -> initializer().run(null));
        verifyNoInteractions(novelRepository, configService);
    }

    @Test
    void backfillFailureMidwayIsLoggedNotRethrown() {
        when(novelRepository.backfillCategoryCode(any(), any()))
                .thenThrow(new RuntimeException("disk on fire"));

        assertDoesNotThrow(() -> initializer().run(null));
        verify(configService, never()).seedDefaults();
    }
}
