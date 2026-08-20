package org.example.fqxs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FanqieNovelCrawlerApplication {

    public static void main(String[] args) {
        // ★★★ 强制设置系统编码为 UTF-8 ★★★
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");

        SpringApplication.run(FanqieNovelCrawlerApplication.class, args);

        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║         番茄小说排行榜爬虫启动成功！                  ║");
        System.out.println("║  API地址: http://localhost:8080/api/rank             ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}