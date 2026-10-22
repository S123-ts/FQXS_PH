package org.example.fqxs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

@Slf4j
@SpringBootApplication
public class FanqieNovelCrawlerApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(FanqieNovelCrawlerApplication.class, args).getEnvironment();
        log.info("番茄小说排行榜爬虫已启动: http://localhost:{}", env.getProperty("server.port", "8080"));
    }
}
