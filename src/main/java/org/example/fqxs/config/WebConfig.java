package org.example.fqxs.config;

import lombok.RequiredArgsConstructor;
import org.example.fqxs.web.AdminTokenInterceptor;
import org.example.fqxs.web.RankWriteInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /**
     * 全量爬取最长可能跑满 分类数 × (max-retries × timeout + 退避)：旧 10 分类 ≈ 465s；
     * 目录扩到 37 个后 ≈ 29 分钟，会超出这里的 10 分钟兜底被截成 503——
     * “展示分类”别勾太满（HANDOVER §1/§14 的预算算式）。
     */
    private static final long ASYNC_REQUEST_TIMEOUT = 600_000L;

    private final AdminTokenInterceptor adminTokenInterceptor;
    private final RankWriteInterceptor rankWriteInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/api/db/**");
        registry.addInterceptor(rankWriteInterceptor).addPathPatterns("/api/rank/**");
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(crawlTaskExecutor());
        configurer.setDefaultTimeout(ASYNC_REQUEST_TIMEOUT);
    }

    /**
     * 全量爬取走这个线程池，避免长时间占用 Tomcat 的请求线程。
     * 池子刻意很小：真正的并发保护由 RankServiceImpl 的爬取锁负责。
     */
    @Bean("crawlTaskExecutor")
    public ThreadPoolTaskExecutor crawlTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("crawl-");
        return executor;
    }
}
