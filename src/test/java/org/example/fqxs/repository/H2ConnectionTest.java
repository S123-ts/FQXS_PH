package org.example.fqxs.repository;

import org.example.fqxs.entity.Novel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 的连接层测试：Hikari 真起连接、多连接并发读写是否互相阻塞或丢行。
 * 用内存库跑（与文件库同一套 MVStore 与锁语义），不碰 data/ 里的真实数据。
 * 每个线程各自开事务，跟应用里 NovelServiceImpl 的 @Transactional 边界一致。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fqxs-conn;DB_CLOSE_DELAY=-1",
        "spring.sql.init.schema-locations=classpath:db/schema-h2.sql"
})
class H2ConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private <T> T inTx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> work.get());
    }

    private void clearTable() {
        inTx(() -> novelRepository.deleteAllRecords());
    }

    private Novel novel(String bookId, String categoryCode, int rank) {
        Novel novel = new Novel();
        novel.setBookId(bookId);
        novel.setTitle("并发-" + bookId);
        novel.setCategoryCode(categoryCode);
        novel.setGender(categoryCode.startsWith("F_") ? 0 : 1);
        novel.setRank(String.valueOf(rank));
        novel.setIsBlocked(false);
        novel.setReadCountRaw(1L);
        return novel;
    }

    @Test
    void poolHandshakeReportsH2Product() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var meta = connection.getMetaData();
            assertThat(meta.getDatabaseProductName()).isEqualTo("H2");
            assertThat(connection.isValid(2)).isTrue();
            System.out.println("H2 版本 = " + meta.getDatabaseProductVersion()
                    + " | 驱动 = " + meta.getDriverVersion());
        }
    }

    @Test
    void concurrentWritersDoNotDeadlockOrLoseRows() throws Exception {
        clearTable();
        int threads = 8;
        int perThread = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int index = t;
                jobs.add(() -> inTx(() -> {
                    List<Novel> batch = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        batch.add(novel("t" + index + "-" + i, "M_SCI_FI", i + 1));
                    }
                    novelRepository.saveAll(batch);
                    // 同一个事务里再读，逼出 H2 的读写并发行为
                    novelRepository.findByCategoryCodeOrderByRankNumeric("M_SCI_FI");
                    return batch.size();
                }));
            }
            int written = 0;
            for (var future : pool.invokeAll(jobs, 60, TimeUnit.SECONDS)) {
                written += future.get();
            }
            assertThat(written).isEqualTo(threads * perThread);
            assertThat(novelRepository.findByCategoryCodeIn(List.of("M_SCI_FI")))
                    .hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPruneAndReadDoNotBlockForever() throws Exception {
        clearTable();
        List<Novel> seed = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            seed.add(novel("p" + i, "M_WESTERN_FANTASY", i));
        }
        inTx(() -> novelRepository.saveAll(seed));
        long before = novelRepository.count();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                int offset = round * 10;
                List<String> keep = new ArrayList<>();
                for (int i = offset + 1; i <= offset + 10; i++) {
                    keep.add("p" + i);
                }
                jobs.add(() -> inTx(() -> novelRepository.deleteStaleByCategoryCode("M_WESTERN_FANTASY", keep)));
                jobs.add(() -> inTx(() ->
                        novelRepository.findByCategoryCodeOrderByRankNumeric("M_WESTERN_FANTASY").size()));
            }
            int touched = 0;
            for (var future : pool.invokeAll(jobs, 60, TimeUnit.SECONDS)) {
                touched += future.get();
            }
            assertThat(touched).isPositive();
            long after = novelRepository.count();
            assertThat(after).isLessThan(before);
            System.out.println("并发清理前后行数 = " + before + " -> " + after);
        } finally {
            pool.shutdownNow();
        }
    }
}
