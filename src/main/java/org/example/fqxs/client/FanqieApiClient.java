package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.fqxs.config.FanqieConfig;
import org.example.fqxs.exception.CrawlException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class FanqieApiClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FanqieConfig fanqieConfig;

    public JsonNode fetchRankUrl(int gender, int subcategoryId, int rankMold, Integer page, Integer size) {
        return fetchRankList(fanqieConfig.buildRankUrl(gender, subcategoryId, rankMold, page, size));
    }

    /**
     * crawler.fanqie.max-retries 之前从未生效，这里真正按配置重试；
     * 4xx 不重试（重试不会改变结果），5xx / 429 / IO 异常按退避重试。
     */
    public JsonNode fetchRankList(String url) {
        int attempts = Math.max(1, fanqieConfig.getMaxRetries());
        IOException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return execute(url);
            } catch (IOException e) {
                lastError = e;
                log.warn("第 {}/{} 次抓取失败: {}", attempt, attempts, e.getMessage());
                if (attempt < attempts && !sleepBackoff(attempt)) {
                    break;
                }
            }
        }
        throw new CrawlException("抓取排行榜失败（已尝试 " + attempts + " 次）: "
                + (lastError == null ? "未知原因" : lastError.getMessage()), lastError);
    }

    private JsonNode execute(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Charset", "UTF-8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", fanqieConfig.getBaseUrl())
                .header("Origin", fanqieConfig.getBaseUrl())
                .build();

        long start = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
            if (!response.isSuccessful()) {
                if (status >= 500 || status == 429) {
                    throw new IOException("上游返回可重试状态码: " + status);
                }
                throw new CrawlException("上游返回 " + status + "，请检查接口参数或是否被风控");
            }
            ResponseBody body = response.body();
            String json = body == null ? "" : new String(body.bytes(), StandardCharsets.UTF_8);
            log.info("抓取成功: {} 字符, 耗时 {} ms", json.length(), System.currentTimeMillis() - start);
            return objectMapper.readTree(json);
        }
    }

    /** @return false 表示线程被中断，调用方应停止重试 */
    private boolean sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("重试等待被中断，停止后续尝试");
            return false;
        }
    }
}
