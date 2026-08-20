package org.example.fqxs.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.fqxs.config.FanqieConfig;
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

    public JsonNode fetchDefaultRankList() throws IOException {
        String url = fanqieConfig.buildDefaultRankUrl();
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║                    📡 爬取地址                           ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  " + url);
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        return fetchRankList(url);
    }

    public JsonNode fetchRankList(Integer rankType, Integer categoryId,
                                  Integer subcategoryId, Integer page, Integer size)
            throws IOException {
        String url = fanqieConfig.buildRankUrl(rankType, categoryId, subcategoryId, page, size);
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║                    📡 爬取地址                           ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  " + url);
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  参数: rankType=" + rankType + ", categoryId=" + categoryId +
                ", subcategoryId=" + subcategoryId + ", page=" + page + ", size=" + size);
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
        return fetchRankList(url);
    }
    public JsonNode fetchRankList(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Charset", "UTF-8")  // ★ 新增：明确要求 UTF-8
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Referer", fanqieConfig.getBaseUrl())
                .header("Origin", fanqieConfig.getBaseUrl())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("请求失败，状态码: " + response.code());
            }
            // ★ 使用 UTF-8 读取
            String json = new String(response.body().bytes(), StandardCharsets.UTF_8);
            log.info("✅ 响应成功，数据长度: {} 字符", json.length());
            System.out.println("✅ 数据获取成功，共 " + json.length() + " 字符\n");
            return objectMapper.readTree(json);
        }
    }
}