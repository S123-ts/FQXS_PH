// src/main/java/org/example/fqxs/controller/RankController.java
package org.example.fqxs.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.constant.CategoryConstant;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.service.RankService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(value = "/api/rank", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class RankController {

    private final RankService rankService;
    private final FanqieConfig fanqieConfig;
    private final NovelService novelService;

    @GetMapping("/default")
    public Map<String, Object> getDefaultRankList() {
        System.out.println("\n🔄 获取默认配置的排行榜...");
        List<NovelInfo> novels = rankService.getDefaultRankList();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", novels);
        result.put("total", novels.size());
        result.put("config", fanqieConfig.getDefaultConfig());
        return result;
    }

    @GetMapping
    public Map<String, Object> getRankList(
            @RequestParam(required = false) CategoryConstant category,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "30") Integer size) {

        if (category == null) {
            category = CategoryConstant.WESTERN_FANTASY;
        }

        log.info("请求: category={}, subcategoryId={}, page={}, size={}",
                category.getName(), category.getSubcategoryId(), page, size);
        System.out.println("\n🔄 请求: " + category.getName() + " (subcategoryId=" + category.getSubcategoryId() + ")");

        List<NovelInfo> novels = rankService.getRankList(category, page, size);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", novels);
        result.put("total", novels.size());
        result.put("category", category.getName());
        result.put("categoryCode", category.getSubcategoryId());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    @GetMapping("/all")
    public Map<String, Object> getAllRankList() {
        System.out.println("\n🔄 获取所有分类排行榜...");
        List<NovelInfo> novels = rankService.getAllRankList();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", novels);
        result.put("total", novels.size());
        return result;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", fanqieConfig);
        return result;
    }

    @GetMapping("/categories")
    public Map<String, Object> getCategories() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        List<Map<String, Object>> categories = new java.util.ArrayList<>();
        for (CategoryConstant c : CategoryConstant.values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("code", c.name());
            item.put("id", c.getSubcategoryId());
            item.put("name", c.getName());
            item.put("rankType", c.getRankType());
            item.put("categoryId", c.getCategoryId());
            categories.add(item);
        }
        result.put("data", categories);
        result.put("total", categories.size());
        return result;
    }

    @GetMapping("/fetchAll")
    public Map<String, Object> fetchAllCategories(
            @RequestParam(defaultValue = "50") Integer size) {
        System.out.println("\n🚀 一键获取所有分类数据，每页 " + size + " 本...");
        log.info("一键获取所有分类数据，每页 {} 本", size);

        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "开始获取所有分类数据...");

        List<Map<String, Object>> categoryResults = new java.util.ArrayList<>();
        int totalBooks = 0;

        for (CategoryConstant category : CategoryConstant.values()) {
            List<NovelInfo> novels = rankService.getRankList(category, 1, size);
            int count = novels.size();
            totalBooks += count;

            Map<String, Object> catResult = new HashMap<>();
            catResult.put("category", category.getName());
            catResult.put("fetched", count);
            categoryResults.add(catResult);

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long endTime = System.currentTimeMillis();
        result.put("data", categoryResults);
        result.put("totalBooks", totalBooks);
        result.put("timeMillis", endTime - startTime);
        result.put("message", "所有分类数据获取完成，共 " + totalBooks + " 本小说");
        System.out.println("✅ 一键获取完成，共 " + totalBooks + " 本小说，耗时 " + (endTime - startTime) + " ms");
        return result;
    }

    // 只修改 /allBooks 方法，其余不变
    @GetMapping("/allBooks")
    public Map<String, Object> getAllBooksFromDb(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String wordRange,
            @RequestParam(required = false) String status) {
        List<String> categoryList = null;
        if (category != null && !category.isEmpty()) {
            String[] parts = category.split(",");
            categoryList = new java.util.ArrayList<>();
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    categoryList.add(trimmed);
                }
            }
            if (categoryList.isEmpty()) {
                categoryList = null;
            }
        }
        List<Novel> novels = novelService.findAllBooksFiltered(categoryList, wordRange, status);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", novels);
        result.put("total", novels.size());
        return result;
    }
    // ★★★ 拉黑接口 ★★★
    @PostMapping("/block/{bookId}")
    public Map<String, Object> blockBook(@PathVariable String bookId) {
        novelService.blockBook(bookId);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "拉黑成功");
        return result;
    }
}