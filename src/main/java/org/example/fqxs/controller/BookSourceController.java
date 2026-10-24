package org.example.fqxs.controller;

import lombok.RequiredArgsConstructor;
import org.example.fqxs.service.BookSourceService;
import org.example.fqxs.web.ApiResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Map;

/**
 * Legado「阅读」书源适配端点。
 *
 * <p>四个只读 GET，全部返回 {@code ApiResult} 信封。安全边界有两条：
 * 本组路径在 {@code LoginInterceptor} 白名单里（阅读 App 没有浏览器会话），
 * 由 {@code BookSourceTokenInterceptor} 按可选配置的令牌单独把关。</p>
 *
 * <p>目录/正文的链接必须是<b>完整地址</b>：阅读端拼相对路径容易出错，
 * 这里用当前请求的根地址拼好再返回。</p>
 */
@RestController
@RequestMapping(value = "/api/bs", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class BookSourceController {

    private final BookSourceService bookSourceService;

    /** 搜索本地库；空 query 回空列表（不能因为漏传参数就把全库吐出去）。 */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(required = false) String query,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        BookSourceService.SearchResult result = bookSourceService.search(query, page, size, baseUrl());
        return ApiResult.page(result.items(), result.total());
    }

    /** 书籍详情：只查本地库，未收录回 code=404（不回退不可靠的第三方详情链路）。 */
    @GetMapping("/info")
    public Map<String, Object> info(@RequestParam String bookId) {
        return bookSourceService.findBook(bookId, baseUrl())
                .<Map<String, Object>>map(view -> ApiResult.data("success", view))
                .orElseGet(() -> ApiResult.fail(404, "本地库未收录该书"));
    }

    /** 章节目录：标题/卷名来自上游，锁章照常列出（正文端点会明确报错）。 */
    @GetMapping("/directory")
    public Map<String, Object> directory(@RequestParam String bookId) {
        String baseUrl = baseUrl();
        /* 先按详情规则确认本地库收录：未收录的书不应触发上游目录请求。
           目录服务内部还会再取一次书名，这里只做存在性判断，不占上游 */
        if (bookSourceService.findBook(bookId, baseUrl).isEmpty()) {
            return ApiResult.fail(404, "本地库未收录该书");
        }
        BookSourceService.DirectoryView view = bookSourceService.directory(bookId, baseUrl);
        Map<String, Object> body = ApiResult.data("success", view);
        body.put("total", view.items().size());
        return body;
    }

    /** 章节正文：已做 PUA 还原与去标签；锁章/风控按失败返回，不伪装成空章。 */
    @GetMapping("/content")
    public Map<String, Object> content(@RequestParam String itemId) {
        return ApiResult.data("success", bookSourceService.content(itemId));
    }

    private String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
