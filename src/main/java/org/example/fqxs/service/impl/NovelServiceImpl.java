// src/main/java/org/example/fqxs/service/impl/NovelServiceImpl.java
package org.example.fqxs.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.entity.Novel;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.repository.NovelRepository;
import org.example.fqxs.service.NovelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NovelServiceImpl implements NovelService {

    private final NovelRepository novelRepository;

    @Override
    @Transactional
    public void saveNovel(NovelInfo novelInfo) {
        saveOrUpdateNovelList(List.of(novelInfo), novelInfo.getCategoryName());
    }

    @Override
    @Transactional
    public void saveOrUpdateNovelList(List<NovelInfo> novelList, String categoryName) {
        if (novelList == null || novelList.isEmpty()) {
            return;
        }

        int insertCount = 0;
        int updateCount = 0;

        for (NovelInfo info : novelList) {
            info.setCategoryName(categoryName);

            Optional<Novel> existingOpt = novelRepository.findByBookId(info.getBookId());

            if (existingOpt.isPresent()) {
                Novel existing = existingOpt.get();
                existing.setRank(info.getRank());
                existing.setTitle(info.getTitle());
                existing.setAuthor(info.getAuthor());
                existing.setDescription(info.getDescription());
                existing.setStatus(info.getStatus());
                existing.setReadCount(info.getReadCount());
                existing.setReadCountRaw(info.getReadCountRaw());
                existing.setLastChapter(info.getLastChapter());
                existing.setUpdateTime(info.getUpdateTime());
                existing.setThumbUri(info.getThumbUri());
                existing.setCategoryName(info.getCategoryName());
                existing.setWordCount(info.getWordCount());
                novelRepository.save(existing);
                updateCount++;
            } else {
                Novel novel = convertToEntity(info);
                novel.setIsBlocked(false);
                novelRepository.save(novel);
                insertCount++;
            }
        }

        log.info("分类 [{}] 更新完成：新增 {} 本，更新 {} 本", categoryName, insertCount, updateCount);
        System.out.println("💾 数据更新完成 (分类: " + categoryName + ")：新增 " + insertCount + " 本，更新 " + updateCount + " 本");
    }

    @Override
    public List<Novel> findNovelsByCategory(String categoryName) {
        return novelRepository.findByCategoryNameOrderByRankAsc(categoryName);
    }

    @Override
    public List<Novel> findAllNovels() {
        return novelRepository.findAll();
    }

    @Override
    public List<Novel> findAllBooksFiltered(List<String> categories, String wordRange, String status) {
        List<Novel> novels;

        if (categories != null && !categories.isEmpty()) {
            novels = novelRepository.findByCategoryNameIn(categories);
        } else {
            novels = novelRepository.findAll();
        }

        // 字数筛选（四个选项）
        if (wordRange != null && !wordRange.isEmpty()) {
            novels = novels.stream()
                    .filter(n -> {
                        String wc = n.getWordCount();
                        if (wc == null || wc.isEmpty()) return false;
                        double num = parseWordCount(wc);
                        switch (wordRange) {
                            case "100w以内": return num <= 100;
                            case "100w以上": return num > 100;      // ★ 包含所有大于100万的
                            case "200w以上": return num > 200;      // ★ 与"100万以上"重叠，更细筛选
                            default: return true;
                        }
                    })
                    .collect(Collectors.toList());
        }

        // 状态筛选
        if (status != null && !status.isEmpty()) {
            novels = novels.stream()
                    .filter(n -> status.equals(n.getStatus()))
                    .collect(Collectors.toList());
        }

        // 过滤拉黑书籍
        novels = novels.stream()
                .filter(n -> n.getIsBlocked() == null || !n.getIsBlocked())
                .collect(Collectors.toList());

        // 按阅读人数降序
        novels.sort((a, b) -> {
            long ra = a.getReadCountRaw() != null ? a.getReadCountRaw() : 0;
            long rb = b.getReadCountRaw() != null ? b.getReadCountRaw() : 0;
            return Long.compare(rb, ra);
        });
        return novels;
    }

    @Override
    @Transactional
    public void blockBook(String bookId) {
        Optional<Novel> novelOpt = novelRepository.findByBookId(bookId);
        if (novelOpt.isEmpty()) {
            log.warn("未找到 bookId: {}", bookId);
            return;
        }
        Novel novel = novelOpt.get();
        novel.setIsBlocked(true);
        novelRepository.save(novel);
        log.info("已拉黑书籍：{} (bookId: {})", novel.getTitle(), bookId);
    }

    @Override
    @Transactional
    public void deleteNovelsByCategory(String categoryName) {
        novelRepository.deleteByCategoryName(categoryName);
    }

    @Override
    @Transactional
    public void deleteAllNovels() {
        novelRepository.deleteAllRecords();
    }

    // ========== 辅助方法 ==========
    private Novel convertToEntity(NovelInfo info) {
        Novel novel = new Novel();
        novel.setRank(info.getRank());
        novel.setBookId(info.getBookId());
        novel.setTitle(info.getTitle());
        novel.setAuthor(info.getAuthor());
        novel.setDescription(info.getDescription());
        novel.setStatus(info.getStatus());
        novel.setReadCount(info.getReadCount());
        novel.setReadCountRaw(info.getReadCountRaw());
        novel.setLastChapter(info.getLastChapter());
        novel.setUpdateTime(info.getUpdateTime());
        novel.setThumbUri(info.getThumbUri());
        novel.setCategoryName(info.getCategoryName());
        novel.setWordCount(info.getWordCount());
        novel.setIsBlocked(false);
        return novel;
    }

    private double parseWordCount(String wc) {
        if (wc == null || wc.isEmpty()) return 0;
        wc = wc.trim();
        if (wc.endsWith("万")) {
            try {
                return Double.parseDouble(wc.substring(0, wc.length() - 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        } else {
            try {
                return Double.parseDouble(wc) / 10000.0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}