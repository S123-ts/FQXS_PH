package org.example.fqxs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.config.BookSourceConfig;
import org.example.fqxs.model.BookSourceView;
import org.example.fqxs.service.BookSourceService;
import org.example.fqxs.web.BookSourceTokenInterceptor;
import org.example.fqxs.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 书源端点契约：信封形状、真实 total、未收录 404，以及可选令牌闸门。 */
@ExtendWith(MockitoExtension.class)
class BookSourceControllerTest {

    @Mock
    private BookSourceService bookSourceService;

    private BookSourceConfig config;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        config = new BookSourceConfig();
        mvc = MockMvcBuilders.standaloneSetup(new BookSourceController(bookSourceService))
                .addInterceptors(new BookSourceTokenInterceptor(config, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void searchReturnsTheRealTotalFromTheService() throws Exception {
        when(bookSourceService.search(eq("游戏"), eq(1), eq(20), anyString())).thenReturn(
                new BookSourceService.SearchResult(List.of(new BookSourceView(
                        "1", "游戏人生", "作者", "cover", "intro", "科幻末世", "10万字",
                        null, null, null, null)), 42));

        mvc.perform(get("/api/bs/search").param("query", "游戏"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.total").value(42))
                .andExpect(jsonPath("$.data[0].bookId").value("1"))
                .andExpect(jsonPath("$.data[0].name").value("游戏人生"))
                .andExpect(jsonPath("$.data[0].status").doesNotExist());
    }

    @Test
    void infoReturns404CodeWhenTheBookIsNotInTheLibrary() throws Exception {
        when(bookSourceService.findBook(eq("missing"), anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/api/bs/info").param("bookId", "missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("本地库未收录该书"));
    }

    @Test
    void directoryPutsTotalAtTheEnvelopeTopLevel() throws Exception {
        when(bookSourceService.findBook(eq("42"), anyString())).thenReturn(Optional.of(
                new BookSourceView("42", "书名", "作者", null, null, null, null,
                        null, null, "http://x/api/bs/directory?bookId=42",
                        "http://x/api/bs/info?bookId=42")));
        when(bookSourceService.directory(eq("42"), anyString())).thenReturn(
                new BookSourceService.DirectoryView("42", "书名", List.of("第一卷"),
                        List.of(new BookSourceService.DirectoryItem(
                                "a", "第1章", "第一卷", "http://x/api/bs/content?itemId=a", false))));

        mvc.perform(get("/api/bs/directory").param("bookId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data.items[0].itemId").value("a"))
                .andExpect(jsonPath("$.data.volumes[0]").value("第一卷"));
    }

    /** 未收录的书不该被拿来触发上游目录请求：与 info 一样回 404，且不进 directory。 */
    @Test
    void directoryReturns404WhenTheBookIsNotInTheLibrary() throws Exception {
        when(bookSourceService.findBook(eq("missing"), anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/api/bs/directory").param("bookId", "missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("本地库未收录该书"));

        verify(bookSourceService, never()).directory(anyString(), anyString());
    }

    @Test
    void contentReturnsTheChapterPayload() throws Exception {
        when(bookSourceService.content("a"))
                .thenReturn(new BookSourceService.ChapterView("a", "第1章", "正文内容"));

        mvc.perform(get("/api/bs/content").param("itemId", "a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.itemId").value("a"))
                .andExpect(jsonPath("$.data.title").value("第1章"))
                .andExpect(jsonPath("$.data.content").value("正文内容"));
    }

    @Test
    void tokenIsEnforcedOnlyWhenConfigured() throws Exception {
        when(bookSourceService.search(any(), anyInt(), anyInt(), anyString()))
                .thenReturn(new BookSourceService.SearchResult(List.of(), 0));

        // 未配置令牌：内网自用直接放行
        mvc.perform(get("/api/bs/search").param("query", "书"))
                .andExpect(status().isOk());

        config.setToken("s3cret");
        mvc.perform(get("/api/bs/search").param("query", "书"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("书源令牌无效"));
        mvc.perform(get("/api/bs/search").param("query", "书")
                        .header(BookSourceTokenInterceptor.TOKEN_HEADER, "s3cret"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/bs/search").param("query", "书").param("token", "s3cret"))
                .andExpect(status().isOk());
    }
}
