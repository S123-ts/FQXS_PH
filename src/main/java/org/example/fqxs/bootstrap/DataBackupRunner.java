package org.example.fqxs.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 启动时做一次 H2 在线备份（{@code BACKUP TO}），保留最近 3 份。
 *
 * <p>直接复制 {@code .mv.db} 在写入时可能拿到不一致快照；H2 的在线备份是原子的。
 * 失败只记日志，不阻断启动（备份是增强，不是启动前提）。</p>
 */
@Slf4j
@Component
@Order(103) // 晚于 schema/播种，备份的是可用状态
@RequiredArgsConstructor
public class DataBackupRunner implements ApplicationRunner {

    private static final Path BACKUP_DIR = Path.of("data", "backup");
    private static final int KEEP_COPIES = 3;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Files.createDirectories(BACKUP_DIR);
            String fileName = "fqxs-" + STAMP.format(LocalDateTime.now()) + ".zip";
            String target = BACKUP_DIR.resolve(fileName).toAbsolutePath().toString().replace('\\', '/');
            jdbcTemplate.execute("BACKUP TO '" + target + "'");
            pruneOldCopies();
            log.info("H2 在线备份完成: {}", target);
        } catch (Exception exception) {
            log.warn("H2 在线备份失败（不影响启动）: {}", exception.getMessage());
        }
    }

    private void pruneOldCopies() throws IOException {
        List<Path> copies;
        try (Stream<Path> files = Files.list(BACKUP_DIR)) {
            copies = files.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (int i = 0; i < copies.size() - KEEP_COPIES; i++) {
            Files.deleteIfExists(copies.get(i));
        }
    }
}
