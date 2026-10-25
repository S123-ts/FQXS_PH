package org.example.fqxs.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 有界队列的契约：满了两万条之后丢弃并计数，绝不无限增长。 */
class LogBufferTest {

    @BeforeEach
    void drainEverything() {
        List<AppLogEntry> app = new ArrayList<>();
        List<ApiLogEntry> api = new ArrayList<>();
        List<ThirdPartyLogEntry> third = new ArrayList<>();
        while (LogBuffer.drainApp(app, LogBuffer.CAPACITY) > 0) {
            app.clear();
        }
        while (LogBuffer.drainApi(api, LogBuffer.CAPACITY) > 0) {
            api.clear();
        }
        while (LogBuffer.drainThirdParty(third, LogBuffer.CAPACITY) > 0) {
            third.clear();
        }
        LogBuffer.resetDroppedCounters();
    }

    @Test
    void dropsWhenQueueIsFullAndCountsIt() {
        long droppedBefore = LogBuffer.droppedApp();
        for (int i = 0; i <= LogBuffer.CAPACITY; i++) {
            LogBuffer.offerApp(appEntry("m" + i));
        }

        assertEquals(1, LogBuffer.droppedApp() - droppedBefore);
        assertEquals(LogBuffer.CAPACITY, LogBuffer.pendingApp());
    }

    @Test
    void nullEntriesAreIgnored() {
        LogBuffer.offerApp(null);
        LogBuffer.offerApi(null);
        LogBuffer.offerThirdParty(null);

        assertEquals(0, LogBuffer.pendingApp());
        assertEquals(0, LogBuffer.pendingApi());
        assertEquals(0, LogBuffer.pendingThirdParty());
    }

    @Test
    void drainReturnsWhatItTook() {
        LogBuffer.offerApp(appEntry("a"));
        LogBuffer.offerApp(appEntry("b"));
        List<AppLogEntry> sink = new ArrayList<>();

        assertEquals(2, LogBuffer.drainApp(sink, 10));
        assertEquals(2, sink.size());
        assertEquals(0, LogBuffer.drainApp(sink, 10));
        assertTrue(sink.get(0).message().equals("a"));
    }

    private static AppLogEntry appEntry(String message) {
        return new AppLogEntry(LocalDateTime.now(), "INFO", "t", "l", null, message, null);
    }
}
