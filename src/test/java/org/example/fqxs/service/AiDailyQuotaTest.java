package org.example.fqxs.service;

import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.exception.QuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 日调用上限（报告 M1）：到顶拒绝、0 表示不限、库读不出来时放行。 */
@ExtendWith(MockitoExtension.class)
class AiDailyQuotaTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AiDetectConfig config;

    private AiDailyQuota quota;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        quota = new AiDailyQuota(jdbcTemplate, config);
    }

    private void todayCalls(long count) {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), any(Timestamp.class)))
                .thenReturn(count);
    }

    @Test
    void rejectsWhenTodayReachesLimit() {
        config.setDailyCallLimit(3);
        todayCalls(3);

        assertThatThrownBy(quota::requireAvailable)
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("今日 AI 调用已达上限");
    }

    @Test
    void allowsBelowLimit() {
        config.setDailyCallLimit(3);
        todayCalls(2);

        assertThatCode(quota::requireAvailable).doesNotThrowAnyException();
    }

    /** 0 或负数 = 不限：连库都不该查（否则每次判定都白扫一次表）。 */
    @Test
    void zeroMeansUnlimitedWithoutQuerying() {
        config.setDailyCallLimit(0);
        quota.requireAvailable();
        config.setDailyCallLimit(-1);
        quota.requireAvailable();

        verifyNoInteractions(jdbcTemplate);
    }

    /**
     * 库不可用必须放行：配额是省钱护栏，不是安全边界。
     * 让它把整条判定链路变成 503 是本末倒置（登录门才是安全边界）。
     */
    @Test
    void failsOpenWhenDatabaseIsUnavailable() {
        config.setDailyCallLimit(1);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), any(Timestamp.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(quota::requireAvailable).doesNotThrowAnyException();
        assertThat(quota.todayCalls()).isEqualTo(-1L);
    }

    /** 计数带 2 秒记忆：一次批量初筛十几本不该十几遍扫表。 */
    @Test
    void countsAreMemoizedForAShortWindow() {
        config.setDailyCallLimit(100);
        todayCalls(7);

        assertThat(quota.todayCalls()).isEqualTo(7L);
        quota.invalidate();
        assertThat(quota.todayCalls()).isEqualTo(7L);
    }
}
