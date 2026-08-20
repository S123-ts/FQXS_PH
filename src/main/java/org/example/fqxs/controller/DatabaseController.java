// src/main/java/org/example/fqxs/controller/DatabaseController.java
package org.example.fqxs.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.service.NovelService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/db")
@RequiredArgsConstructor
public class DatabaseController {

    private final NovelService novelService;

    /**
     * 查询所有小说数据
     * GET /api/db/all
     */
    @GetMapping("/all")
    public Map<String, Object> getAllFromDb() {
        List<Novel> novels = novelService.findAllNovels();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", novels);
        result.put("total", novels.size());
        return result;
    }

    /**
     * 按分类查询小说数据
     * GET /api/db/category?name=西方奇幻
     */
    @GetMapping("/category")
    public Map<String, Object> getByCategory(@RequestParam String name) {
        List<Novel> novels = novelService.findNovelsByCategory(name);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", novels);
        result.put("total", novels.size());
        result.put("category", name);
        return result;
    }

    /**
     * 清空指定分类的数据
     * DELETE /api/db/category?name=西方奇幻
     */
    @DeleteMapping("/category")
    public Map<String, Object> deleteByCategory(@RequestParam String name) {
        novelService.deleteNovelsByCategory(name);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("deletedCategory", name);
        return result;
    }

    /**
     * 清空所有数据
     * DELETE /api/db/all
     */
    @DeleteMapping("/all")
    public Map<String, Object> deleteAll() {
        novelService.deleteAllNovels();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        return result;
    }
}