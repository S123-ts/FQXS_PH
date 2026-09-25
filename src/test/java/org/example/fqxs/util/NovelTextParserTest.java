package org.example.fqxs.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NovelTextParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void prefersCamelCaseAndFallsBackToSnakeCase() throws Exception {
        assertEquals("7159", NovelTextParser.firstText(json("{\"bookId\":\"7159\"}"), "bookId", "book_id"));
        assertEquals("7159", NovelTextParser.firstText(json("{\"book_id\":\"7159\"}"), "bookId", "book_id"));
        assertEquals("", NovelTextParser.firstText(json("{}"), "bookId", "book_id"));
        assertEquals("", NovelTextParser.firstText(null, "bookId"));
    }

    @Test
    void skipsBlankValuesWhenLookingForFallbacks() throws Exception {
        assertEquals("123", NovelTextParser.firstText(json("{\"bookId\":\"\",\"book_id\":\"123\"}"), "bookId", "book_id"));
    }

    @Test
    void firstLongAcceptsNumericStringsAndToleratesJunk() throws Exception {
        assertEquals(45000L, NovelTextParser.firstLong(json("{\"read_count\":\"45000\"}"), "read_count"));
        assertEquals(45000L, NovelTextParser.firstLong(json("{\"read_count\":45000}"), "read_count"));
        assertEquals(0L, NovelTextParser.firstLong(json("{\"read_count\":\"热度爆表\"}"), "read_count"));
    }

    @Test
    void parseStatusNormalizesCodesAndText() throws Exception {
        assertEquals("已完结", NovelTextParser.parseStatus(json("{\"creationStatus\":0}")));
        assertEquals("连载中", NovelTextParser.parseStatus(json("{\"creation_status\":\"1\"}")));
        assertEquals("已完结", NovelTextParser.parseStatus(json("{\"status\":\"完结\"}")));
        assertEquals("未知", NovelTextParser.parseStatus(json("{}")));
    }

    @Test
    void formatCountFoldsLargeNumbersIntoWan() throws Exception {
        assertEquals("1.5万", NovelTextParser.formatCount(15000L));
        assertEquals("9999", NovelTextParser.formatCount(9999L));
        assertEquals("12.3万", NovelTextParser.formatCount(json("{\"wordNumber\":123456}"), "wordNumber"));
        assertEquals("热度爆表", NovelTextParser.formatCount(json("{\"wordNumber\":\"热度爆表\"}"), "wordNumber"));
    }

    @Test
    void parseWordCountToWanHandlesBothNotations() {
        assertEquals(120.0, NovelTextParser.parseWordCountToWan("120万"), 0.001);
        assertEquals(120.0, NovelTextParser.parseWordCountToWan("1200000"), 0.001);
        assertEquals(0.0, NovelTextParser.parseWordCountToWan("断更"), 0.001);
        assertEquals(0.0, NovelTextParser.parseWordCountToWan(null), 0.001);
    }

    @Test
    void fieldNamesHelpsDiagnoseUpstreamChanges() throws Exception {
        assertEquals(List.of("bookId", "bookName"),
                NovelTextParser.fieldNames(json("{\"bookId\":1,\"bookName\":2}")));
        assertEquals(List.of(), NovelTextParser.fieldNames(null));
    }
}
