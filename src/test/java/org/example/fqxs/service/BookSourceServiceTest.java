package org.example.fqxs.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.fqxs.client.ChapterClient;
import org.example.fqxs.config.BookSourceConfig;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.BookSourceView;
import org.example.fqxs.model.ChapterRef;
import org.example.fqxs.model.ChapterSample;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.util.PuaDecoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookSourceServiceTest {

    @Mock
    private NovelRepository novelRepository;

    @Mock
    private ChapterClient chapterClient;

    /** 目录取数（含第三方回退）已抽到 ChapterDirectoryService，书源只管拼链接。 */
    @Mock
    private ChapterDirectoryService directoryService;

    @Mock
    private UpstreamThrottle upstreamThrottle;

    private BookSourceConfig config;
    private BookSourceService service;

    @BeforeEach
    void setUp() {
        config = new BookSourceConfig();
        // 单测不真的等待上游节拍
        config.setRequestIntervalMillis(123);
        config.setSearchSizeMax(50);
        service = new BookSourceService(novelRepository, chapterClient, directoryService, config,
                upstreamThrottle, new PuaDecoder(new ObjectMapper()));
    }

    @Test
    void blankQueryReturnsEmptyWithoutTouchingTheDatabase() {
        BookSourceService.SearchResult result = service.search("   ", 1, 20, "http://host");

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.total());
        verifyNoInteractions(novelRepository);
        verifyNoInteractions(upstreamThrottle);
    }

    @Test
    void pageAndSizeAreClampedBeforeQuerying() {
        when(novelRepository.searchByKeyword(anyString(), anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        when(novelRepository.countSearchKeyword(anyString())).thenReturn(0L);

        service.search("游戏", 0, 999, "http://host");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(novelRepository).searchByKeyword(anyString(), anyString(), anyString(), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber(), "page 传 0 要钳到第一页");
        assertEquals(50, pageable.getValue().getPageSize(), "size 上限由配置钳制");
    }

    /** 关键词里的 % 必须按字面量搜，否则一次「100%」会把全库拖进 LIKE。 */
    @Test
    void wildcardsInKeywordAreEscaped() {
        when(novelRepository.searchByKeyword(anyString(), anyString(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        when(novelRepository.countSearchKeyword(anyString())).thenReturn(0L);

        service.search("100%_a", 1, 10, "http://host");

        ArgumentCaptor<String> like = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(novelRepository).searchByKeyword(eq("100%_a"), like.capture(), prefix.capture(), any(Pageable.class));
        assertEquals("%100!%!_a%", like.getValue());
        assertEquals("100!%!_a%", prefix.getValue());
    }

    @Test
    void overlyLongKeywordIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search("书".repeat(BookSourceService.MAX_KEYWORD_LENGTH + 1), 1, 10, "http://host"));
        verifyNoInteractions(novelRepository);
    }

    @Test
    void infoReturnsDecodedBookWithFullTocUrl() {
        Novel novel = new Novel();
        novel.setId(1L);
        novel.setBookId("42");
        novel.setTitle("\uE3E8书");
        novel.setAuthor("作者");
        novel.setDescription("简介");
        novel.setCategoryName("科幻末世");
        novel.setWordCount("196.4万字");
        novel.setStatus("连载中");
        novel.setLastChapter("第1章");
        novel.setThumbUri("https://cdn.example/cover.jpg");
        when(novelRepository.findAllByBookId("42")).thenReturn(List.of(novel));

        Optional<org.example.fqxs.model.BookSourceView> view = service.findBook("42", "http://192.168.1.5:8080/");

        assertTrue(view.isPresent());
        assertEquals("D书", view.get().name(), "PUA 在出口兜底还原");
        assertEquals("https://cdn.example/cover.jpg", view.get().cover());
        assertEquals("连载中", view.get().status());
        assertEquals("http://192.168.1.5:8080/api/bs/directory?bookId=42", view.get().tocUrl());
        assertEquals("http://192.168.1.5:8080/api/bs/info?bookId=42", view.get().bookUrl());
        verifyNoInteractions(upstreamThrottle); // 详情只查库，不占上游节拍
    }

    @Test
    void infoIsEmptyWhenBookIsNotInTheLocalLibrary() {
        when(novelRepository.findAllByBookId("missing")).thenReturn(List.of());

        assertTrue(service.findBook("missing", "http://host").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.findBook(" ", "http://host"));
    }

    @Test
    void directoryReturnsVolumesAndFullContentUrls() {
        when(novelRepository.findAllByBookId("42")).thenReturn(List.of(novel("42", "书名")));
        when(directoryService.listChapters(eq("42"), anyLong())).thenReturn(List.of(
                new ChapterRef("a", "第1章", "第一卷", false),
                new ChapterRef("b", "第2章", "第一卷", true),
                new ChapterRef("c", "第3章", "第二卷", false)));

        BookSourceService.DirectoryView view = service.directory("42", "http://host:8080/");

        assertEquals("书名", view.bookName());
        assertEquals(List.of("第一卷", "第二卷"), view.volumes(), "卷名保序去重");
        assertEquals(3, view.items().size());
        assertEquals("http://host:8080/api/bs/content?itemId=a", view.items().get(0).url(),
                "正文地址必须是完整地址，阅读端不拼相对路径");
        assertFalse(view.items().get(0).locked());
        assertTrue(view.items().get(1).locked());
        verify(directoryService).listChapters("42", 123); // 目录的上游节流交给 ChapterDirectoryService
    }

    /** 正文缓存命中时不再回源，不能白占共享节流槽（否则 AI 判定也被推迟）。 */
    @Test
    void cachedChapterDoesNotOccupyTheUpstreamSlot() {
        when(novelRepository.findAllByBookId("42")).thenReturn(List.of(novel("42", "书名")));
        when(directoryService.listChapters(eq("42"), anyLong())).thenReturn(List.of(
                new ChapterRef("a", "第1章", "第一卷", false)));
        when(chapterClient.hasCachedChapter("a")).thenReturn(true);
        when(chapterClient.fetchChapter("a")).thenReturn(new ChapterSample("a", "第1章", "正文", 2));

        assertEquals(1, service.directory("42", "http://host").items().size());
        assertEquals("正文", service.content("a").content());

        verifyNoInteractions(upstreamThrottle);
    }

    /** 令牌必须拼进返回给阅读端的绝对链接，否则 App 直接请求目录/正文会 401。 */
    @Test
    void tokenIsAppendedToEveryAbsoluteLink() {
        config.setToken("s3cret");
        when(novelRepository.findAllByBookId("42")).thenReturn(List.of(novel("42", "书名")));
        when(directoryService.listChapters(eq("42"), anyLong())).thenReturn(List.of(
                new ChapterRef("a", "第1章", "第一卷", false)));

        BookSourceView info = service.findBook("42", "http://host:8080/").orElseThrow();
        assertEquals("http://host:8080/api/bs/info?bookId=42&token=s3cret", info.bookUrl());
        assertEquals("http://host:8080/api/bs/directory?bookId=42&token=s3cret", info.tocUrl());

        BookSourceService.DirectoryView directory = service.directory("42", "http://host:8080/");
        assertEquals("http://host:8080/api/bs/content?itemId=a&token=s3cret",
                directory.items().get(0).url());
    }

    @Test
    void contentReturnsDecodedChapter() {
        when(chapterClient.fetchChapter("a")).thenReturn(new ChapterSample("a", "\uE3E8章", "正文内容", 4));

        BookSourceService.ChapterView view = service.content("a");

        assertEquals("D章", view.title());
        assertEquals("正文内容", view.content());
        verify(upstreamThrottle).awaitSlot(123); // 正文回源前必须占节流槽
    }

    @Test
    void idsAreRequiredOnEveryLookup() {
        assertThrows(IllegalArgumentException.class, () -> service.directory(" ", "http://host"));
        assertThrows(IllegalArgumentException.class, () -> service.content(null));
    }

    private Novel novel(String bookId, String title) {
        Novel novel = new Novel();
        novel.setId(1L);
        novel.setBookId(bookId);
        novel.setTitle(title);
        return novel;
    }
}
