package org.example.fqxs.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.ConfigService;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库解码上线之前落库的书名、作者、简介是上游 PUA 混淆文本，启动时必须回填成汉字。
 *
 * <p>三条约束：只改含 PUA 的行（干净行不产生写操作）、只写四个文本列、
 * 跑过一遍后再启动不发 UPDATE（幂等）。</p>
 */
@ExtendWith(MockitoExtension.class)
class StartupDataInitializerPuaBackfillTest {

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private ConfigService configService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private final PuaDecoder puaDecoder = new PuaDecoder(new ObjectMapper());

    private StartupDataInitializer initializer() {
        return new StartupDataInitializer(novelRepository, configService, puaDecoder, jdbcTemplate,
                new TransactionTemplate(transactionManager));
    }

    private void givenRows(List<StartupDataInitializer.TextRow> rows) {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doReturn(rows).when(jdbcTemplate).query(anyString(), any(RowMapper.class));
    }

    /** U+E3E8 是映射表首项，固定还原成 "D"，用它做锚点避免测试自证。 */
    @Test
    void backfillRewritesOnlyPollutedRows() {
        givenRows(List.of(
                new StartupDataInitializer.TextRow(1L, "\uE3E8书", "\uE3E8者", "简介\uE3E8", null),
                new StartupDataInitializer.TextRow(2L, "干净书名", "干净作者", "干净简介", "第一章")));

        initializer().run(null);

        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        verify(jdbcTemplate).batchUpdate(anyString(), batch.capture());

        assertEquals(1, batch.getValue().size(), "干净行不该被写库");
        Object[] args = batch.getValue().get(0);
        assertEquals("D书", args[0]);
        assertEquals("D者", args[1]);
        assertEquals("简介D", args[2]);
        assertEquals(1L, ((Number) args[4]).longValue());
    }

    /** 回填后的文本已不含 PUA，下次启动整表扫描应命中 0 行、不发任何 UPDATE。 */
    @Test
    void secondRunIssuesNoUpdate() {
        givenRows(List.of(
                new StartupDataInitializer.TextRow(1L, "D书", "D者", "简介D", "D章")));

        initializer().run(null);

        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(List.class));
    }
}
