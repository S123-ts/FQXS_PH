// src/main/java/org/example/fqxs/service/impl/RankServiceImpl.java
package org.example.fqxs.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.FanqieApiClient;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.constant.CategoryConstant;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.service.NovelService;
import org.example.fqxs.service.RankService;
import org.example.fqxs.util.DateUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {

    private final FanqieApiClient apiClient;
    private final FanqieConfig fanqieConfig;
    private final NovelService novelService;

    @Override
    public List<NovelInfo> getRankList(CategoryConstant category, Integer page, Integer size) {
        List<NovelInfo> novels = new ArrayList<>();

        log.info("开始爬取: category={}, subcategoryId={}, page={}, size={}",
                category.getName(), category.getSubcategoryId(), page, size);
        System.out.println("\n📖 开始爬取: " + category.getName() + " (subcategoryId=" + category.getSubcategoryId() + ")");

        try {
            JsonNode root = apiClient.fetchRankList(
                    category.getRankType(),
                    category.getCategoryId(),
                    category.getSubcategoryId(),
                    page,
                    size
            );

            // 解析数据（兼容两种接口返回结构）
            JsonNode listNode = root.path("data").path("book_list");
            if (!listNode.isArray()) {
                listNode = root.path("data").path("list");
            }
            if (!listNode.isArray()) {
                listNode = root.path("list");
            }

            if (!listNode.isArray()) {
                log.warn("分类[{}]未获取到列表数据", category.getName());
                System.out.println("⚠️ 未获取到数据");
                return novels;
            }

            int rank = (page - 1) * size + 1;
            for (JsonNode book : listNode) {
                NovelInfo info = parseNovel(book, String.valueOf(rank++));
                info.setCategoryName(category.getName());
                novels.add(info);
            }

            log.info("✅ 成功获取分类[{}]的排行榜数据，共{}条", category.getName(), novels.size());
            System.out.println("✅ 获取到 " + novels.size() + " 条数据");

            // ★★★ 增量更新：存在则更新，不存在则插入，保留拉黑状态 ★★★
            if (!novels.isEmpty()) {
                novelService.saveNovelList(novels, category.getName());
                System.out.println("💾 数据已保存到数据库 (分类: " + category.getName() + "，保留拉黑状态)");
            }

        } catch (IOException e) {
            log.error("❌ 获取排行榜数据失败: {}", e.getMessage(), e);
            System.out.println("❌ 获取失败: " + e.getMessage());
        }

        return novels;
    }

    @Override
    public List<NovelInfo> getDefaultRankList() {
        List<NovelInfo> novels = new ArrayList<>();
        System.out.println("\n📖 使用默认配置爬取...");
        try {
            JsonNode root = apiClient.fetchDefaultRankList();

            JsonNode listNode = root.path("data").path("book_list");
            if (!listNode.isArray()) {
                listNode = root.path("data").path("list");
            }
            if (!listNode.isArray()) {
                listNode = root.path("list");
            }
            if (!listNode.isArray()) {
                log.warn("未获取到列表数据");
                return novels;
            }

            String categoryName = getCategoryName(fanqieConfig.getDefaultConfig().getSubcategoryId());
            int rank = 1;
            for (JsonNode book : listNode) {
                NovelInfo info = parseNovel(book, String.valueOf(rank++));
                info.setCategoryName(categoryName);
                novels.add(info);
            }

            log.info("✅ 成功获取默认排行榜数据，共{}条", novels.size());
            System.out.println("✅ 获取到 " + novels.size() + " 条数据");

            if (!novels.isEmpty()) {
                novelService.saveNovelList(novels, categoryName);
                System.out.println("💾 数据已保存到数据库 (分类: " + categoryName + "，保留拉黑状态)");
            }

        } catch (IOException e) {
            log.error("❌ 获取默认排行榜数据失败: {}", e.getMessage(), e);
            System.out.println("❌ 获取失败: " + e.getMessage());
        }
        return novels;
    }

    @Override
    public List<NovelInfo> getAllRankList() {
        List<NovelInfo> allNovels = new ArrayList<>();
        System.out.println("\n📖 开始获取所有分类...");
        for (CategoryConstant category : CategoryConstant.values()) {
            // getRankList 方法已包含保存逻辑
            List<NovelInfo> novels = getRankList(category, 1, 30);
            allNovels.addAll(novels);
            try {
                Thread.sleep(100); // 礼貌性延迟
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("✅ 所有分类共获取 {} 本小说", allNovels.size());
        System.out.println("✅ 所有分类共获取 " + allNovels.size() + " 本小说\n");
        return allNovels;
    }

    @Override
    public Integer getTotalCount(CategoryConstant category) {
        try {
            JsonNode root = apiClient.fetchRankList(
                    category.getRankType(),
                    category.getCategoryId(),
                    category.getSubcategoryId(),
                    1,
                    1
            );
            return root.path("data").path("total_num").asInt(
                    root.path("data").path("total").asInt(0)
            );
        } catch (IOException e) {
            log.error("获取总数失败: {}", e.getMessage());
            return 0;
        }
    }

    // ========== 工具方法 ==========

    private String getCategoryName(Integer subcategoryId) {
        for (CategoryConstant category : CategoryConstant.values()) {
            if (category.getSubcategoryId().equals(subcategoryId)) {
                return category.getName();
            }
        }
        return "未知分类";
    }

    private NovelInfo parseNovel(JsonNode book, String rank) {
        return NovelInfo.builder()
                .rank(rank)
                .bookId(getText(book, "bookId"))
                .title(getText(book, "bookName"))
                .author(getText(book, "author"))
                .description(getText(book, "abstract"))
                .status(parseStatus(book))
                .readCount(formatReadCount(book))
                .readCountRaw(getLong(book, "read_count"))
                .lastChapter(getText(book, "lastChapterTitle"))
                .updateTime(parseTimestamp(book))
                .thumbUri(getText(book, "thumbUri"))
                .wordCount(formatWordCount(book))
                .build();
    }

    private String getText(JsonNode node, String fieldName) {
        JsonNode child = node.path(fieldName);
        return (!child.isMissingNode() && !child.isNull()) ? child.asText() : "";
    }

    private Long getLong(JsonNode node, String fieldName) {
        JsonNode child = node.path(fieldName);
        return (!child.isMissingNode() && !child.isNull()) ? child.asLong() : 0L;
    }

    private String parseStatus(JsonNode book) {
        String status = getText(book, "creationStatus");
        if (status.isEmpty()) {
            status = getText(book, "status");
        }
        if (status.isEmpty()) return "未知";
        if (status.matches("\\d+")) {
            int code = Integer.parseInt(status);
            return code == 0 ? "已完结" : "连载中";
        }
        if (status.contains("连载")) return "连载中";
        if (status.contains("完结")) return "已完结";
        return status;
    }

    private String formatReadCount(JsonNode book) {
        String count = getText(book, "read_count");
        if (count.isEmpty()) {
            count = getText(book, "readCount");
        }
        if (count.isEmpty()) return "";
        try {
            long num = Long.parseLong(count);
            return num >= 10000 ? String.format("%.1f万", num / 10000.0) : String.valueOf(num);
        } catch (NumberFormatException e) {
            return count;
        }
    }

    private String formatWordCount(JsonNode book) {
        String count = getText(book, "wordNumber");
        if (count.isEmpty()) {
            count = getText(book, "wordCount");
        }
        if (count.isEmpty()) return "";
        try {
            long num = Long.parseLong(count);
            return num >= 10000 ? String.format("%.1f万", num / 10000.0) : String.valueOf(num);
        } catch (NumberFormatException e) {
            return count;
        }
    }

    private String parseTimestamp(JsonNode book) {
        String timeStr = getText(book, "lastChapterUpdateTime");
        if (timeStr.isEmpty()) {
            timeStr = getText(book, "updateTime");
        }
        if (timeStr.isEmpty()) {
            timeStr = getText(book, "lastUpdateTime");
        }
        if (timeStr.isEmpty()) return "";
        try {
            long timestamp = Long.parseLong(timeStr);
            return DateUtil.formatTimestamp(timestamp);
        } catch (NumberFormatException e) {
            return timeStr;
        }
    }
}