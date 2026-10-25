package org.example.fqxs.config;

import lombok.RequiredArgsConstructor;
import org.example.fqxs.logging.ApiLogInterceptor;
import org.example.fqxs.web.AdminTokenInterceptor;
import org.example.fqxs.web.BookSourceTokenInterceptor;
import org.example.fqxs.web.LoginInterceptor;
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
     * 全量爬取最长耗时约 分类数 × (max-retries × timeout + 退避)：10 分类约 465s；
     * 37 分类约 29 分钟，超出此处的 10 分钟兜底后会被截断为 503——
     * “展示分类”不宜过满。
     */
    private static final long ASYNC_REQUEST_TIMEOUT = 600_000L;

    private final AdminTokenInterceptor adminTokenInterceptor;
    private final RankWriteInterceptor rankWriteInterceptor;
    private final LoginInterceptor loginInterceptor;
    private final BookSourceTokenInterceptor bookSourceTokenInterceptor;
    private final ApiLogInterceptor apiLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API 日志必须排在最前：Spring 只回调「已成功执行 preHandle」的拦截器的 afterCompletion，
        // 排在登录门后面就漏掉所有 401——而扫接口的请求正是要被记下来的那批
        registry.addInterceptor(apiLogInterceptor).addPathPatterns("/api/**");
        // 登录门排第二：未登录的请求在进入其它拦截器之前就被挡下，
        // 免得「没登录」被报成「写令牌不对」，把排查方向带偏
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(LoginInterceptor.WHITELIST);
        registry.addInterceptor(adminTokenInterceptor).addPathPatterns("/api/db/**");
        // /api/ai/** 会触发外部模型调用与写库，与抓取接口同一套会话写令牌保护；
        // 只读的 GET /api/ai/status 由拦截器内部按方法放行
        registry.addInterceptor(rankWriteInterceptor).addPathPatterns("/api/rank/**", "/api/ai/**");
        // 书源端点在登录白名单里（阅读 App 无会话），由这层可选令牌单独把关
        registry.addInterceptor(bookSourceTokenInterceptor).addPathPatterns("/api/bs/**");
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
