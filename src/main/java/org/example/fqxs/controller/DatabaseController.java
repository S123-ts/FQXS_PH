package org.example.fqxs.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.web.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据库维护接口。GET 会导出整表原始行，DELETE 不可逆，
 * 因此所有方法都由 AdminTokenInterceptor 要求 X-Admin-Token；前端不依赖这里。
 * 分类定位一律用全局唯一 code（M_/F_），与 novel_rank.category_code 对应。
 */
@Slf4j
@RestController
@RequestMapping("/api/db")
@RequiredArgsConstructor
public class DatabaseController {

    private final NovelService novelService;

    @GetMapping("/all")
    public Map<String, Object> getAllFromDb() {
        List<Novel> novels = novelService.findAllNovels();
        return ApiResult.list(novels);
    }

    @GetMapping("/category")
    public Map<String, Object> getByCategory(@RequestParam String code) {
        List<Novel> novels = novelService.findNovelsByCategoryCode(code);
        Map<String, Object> body = ApiResult.list(novels);
        body.put("categoryCode", code);
        return body;
    }

    @DeleteMapping("/category")
    public Map<String, Object> deleteByCategory(@RequestParam String code) {
        novelService.deleteNovelsByCategoryCode(code);
        Map<String, Object> body = ApiResult.ok("分类数据已清空");
        body.put("deletedCategoryCode", code);
        return body;
    }

    @DeleteMapping("/all")
    public Map<String, Object> deleteAll() {
        novelService.deleteAllNovels();
        return ApiResult.ok("novel_rank 已清空");
    }
}
