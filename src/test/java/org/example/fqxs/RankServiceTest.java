// src/test/java/org/example/fqxs/RankServiceTest.java
package org.example.fqxs;

import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.constant.CategoryConstant;
import org.example.fqxs.model.NovelInfo;
import org.example.fqxs.service.RankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
public class RankServiceTest {

    @Autowired
    private RankService rankService;

    @Test
    public void testGetWesternFantasy() {
        List<NovelInfo> novels = rankService.getRankList(CategoryConstant.WESTERN_FANTASY);
        log.info("西方奇幻榜共 {} 本", novels.size());
        novels.forEach(novel ->
                log.info("排名: {}, 书名: {}, 作者: {}, 在读: {}",
                        novel.getRank(), novel.getTitle(), novel.getAuthor(), novel.getReadCount())
        );
    }

    @Test
    public void testGetWarGod() {
        List<NovelInfo> novels = rankService.getRankList(CategoryConstant.WAR_GOD);
        log.info("战神赘婿榜共 {} 本", novels.size());
        novels.forEach(novel ->
                log.info("排名: {}, 书名: {}, 作者: {}",
                        novel.getRank(), novel.getTitle(), novel.getAuthor())
        );
    }

    @Test
    public void testGetAllRank() {
        List<NovelInfo> novels = rankService.getAllRankList();
        log.info("所有分类共获取 {} 本小说", novels.size());
    }
}