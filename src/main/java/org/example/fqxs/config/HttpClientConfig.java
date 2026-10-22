package org.example.fqxs.config;

import lombok.RequiredArgsConstructor;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final FanqieConfig fanqieConfig;

    @Bean
    public OkHttpClient okHttpClient() {
        long timeout = Math.max(1000, fanqieConfig.getTimeout());
        return new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                // 仅重试连接层失败；HTTP 层重试由 FanqieApiClient 按 max-retries 控制
                .retryOnConnectionFailure(false)
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build();
    }
}
