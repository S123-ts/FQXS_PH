package org.example.fqxs.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 落库消费者的契约：三类日志各写一张表、开关关掉时排空但不写。 */
@ExtendWith(MockitoExtension.class)
class LogDbWriterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private LogDbConfig config;

    private LogDbWriter writer;

    @BeforeEach
    void setUp() {
        config = new LogDbConfig();
        writer = new LogDbWriter(jdbcTemplate, config);
        drainAll();
    }

    @Test
    void flushesAllThreeKindsInOneRound() {
        LogBuffer.offerApp(new AppLogEntry(LocalDateTime.now(), "INFO", "t", "l", "42", "m", null));
        LogBuffer.offerApi(new ApiLogEntry(LocalDateTime.now(), ApiLogEntry.DIRECTION_IN, "GET",
                "/api/x", null, null, 200, 3, null, "127.0.0.1", null, null,
                ApiLogEntry.OUTCOME_OK, null));
        LogBuffer.offerThirdParty(new ThirdPartyLogEntry(LocalDateTime.now(), "https://line", "/info",
                "第三方详情", "book_id", "42", "42", 200, 100, 12, ThirdPartyLogEntry.OK, null));

        assertEquals(3, writer.flushAll());

        assertEquals(0, LogBuffer.pendingApp());
        assertEquals(0, LogBuffer.pendingApi());
        assertEquals(0, LogBuffer.pendingThirdParty());
        verify(jdbcTemplate, times(3))
                .batchUpdate(anyString(), any(List.class), anyInt(), any());
    }

    @Test
    void disabledKindIsDrainedWithoutWriting() {
        config.setAppEnabled(false);
        LogBuffer.offerApp(new AppLogEntry(LocalDateTime.now(), "INFO", "t", "l", null, "m", null));

        assertEquals(1, writer.flushAll());

        assertEquals(0, LogBuffer.pendingApp());
        verifyNoInteractions(jdbcTemplate);
    }

    /** 总开关关掉：队列照样排空（否则两万条白占内存），但一条都不写。 */
    @Test
    void masterSwitchOffStillDrainsWithoutWriting() {
        config.setEnabled(false);
        LogBuffer.offerApp(new AppLogEntry(LocalDateTime.now(), "INFO", "t", "l", null, "m", null));
        LogBuffer.offerApi(new ApiLogEntry(LocalDateTime.now(), ApiLogEntry.DIRECTION_IN, "GET",
                "/api/x", null, null, 200, 1, null, null, null, null, ApiLogEntry.OUTCOME_OK, null));

        assertEquals(2, writer.flushAll());

        assertEquals(0, LogBuffer.pendingApp());
        assertEquals(0, LogBuffer.pendingApi());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void emptyQueuesDoNotTouchTheDatabase() {
        assertEquals(0, writer.flushAll());

        verifyNoInteractions(jdbcTemplate);
    }

    private static void drainAll() {
        List<AppLogEntry> app = new ArrayList<>();
        List<ApiLogEntry> api = new ArrayList<>();
        List<ThirdPartyLogEntry> third = new ArrayList<>();
        LogBuffer.drainApp(app, LogBuffer.CAPACITY);
        LogBuffer.drainApi(api, LogBuffer.CAPACITY);
        LogBuffer.drainThirdParty(third, LogBuffer.CAPACITY);
    }
}
