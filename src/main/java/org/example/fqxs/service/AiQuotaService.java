package org.example.fqxs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.example.fqxs.config.AiDetectConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 引擎的额度查询。
 *
 * <p>这里只做"平台真的开放了接口"的部分，不做猜测：</p>
 * <ul>
 *   <li><b>硅基流动</b>：有 {@code GET /v1/user/info}，能拿到赠送余额、充值余额与合计；</li>
 *   <li><b>智谱</b>：没有公开的余额/用量接口（2026-10-21 实测 {@code /api/paas/v4/account/balance}、
 *       {@code /balance}、{@code /user/info}、{@code /usage} 全 404），只能给控制台入口与静态说明；</li>
 *   <li><b>EdgeOne Makers</b>：同样没有公开的额度接口，50 万 token/月是固定规则。</li>
 * </ul>
 *
 * <p>有效期：三家都没有在 API 里返回到期时间，页面只能标注规则（免费档无到期 / 赠送额度以控制台为准），
 * 不臆造日期。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    /** 硅基流动账户信息接口（余额在 data.balance / chargeBalance / totalBalance）。 */
    private static final String QWEN_USER_INFO = "https://api.siliconflow.cn/v1/user/info";

    private static final String GLM_CONSOLE = "https://open.bigmodel.cn/finance-center/resource-package/package-mgmt";
    private static final String GLM_HOME = "https://open.bigmodel.cn/";
    private static final String QWEN_CONSOLE = "https://cloud.siliconflow.cn/me/account/overview";

    private final AiDetectConfig aiDetectConfig;

    private final OkHttpClient httpClient;

    private final ObjectMapper objectMapper;

    /**
     * 查询三个引擎的额度情况。任一引擎失败不影响其它引擎，也不会抛出到接口层。
     *
     * @return 引擎标识 → 额度信息
     */
    public Map<String, Map<String, Object>> query() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        result.put(AiDetectConfig.ENGINE_GLM, glmQuota());
        result.put(AiDetectConfig.ENGINE_QWEN, qwenQuota());
        return result;
    }

    /** 智谱：没有余额接口，按模型是否免费给出静态说明；链接按是否已配置分流。 */
    private Map<String, Object> glmQuota() {
        Map<String, Object> item = new LinkedHashMap<>();
        String model = aiDetectConfig.getGlmModel();
        boolean configured = notBlank(aiDetectConfig.getGlmApiKey());
        boolean free = FreeModelCatalog.isFree(AiDetectConfig.ENGINE_GLM, model);
        item.put("supported", false);
        item.put("configured", configured);
        item.put("free", free);
        // 已配置才给资源包管理页（看额度）；没配置给首页，引导去注册/开通
        item.put("consoleUrl", configured ? GLM_CONSOLE : GLM_HOME);
        item.put("note", free
                ? "不限量，受 30 并发与高峰限流约束，无到期日"
                : "余额与有效期需到控制台查看，按 token 计费");
        item.put("reason", "智谱未开放余额查询接口");
        return item;
    }

    /** 硅基流动：真实余额（赠送 / 充值 / 合计）。 */
    private Map<String, Object> qwenQuota() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("supported", true);
        String key = aiDetectConfig.getQwenApiKey();
        if (!notBlank(key)) {
            item.put("configured", false);
            item.put("note", "未配置密钥");
            item.put("consoleUrl", QWEN_CONSOLE);
            return item;
        }
        item.put("configured", true);

        Request request = new Request.Builder()
                .url(QWEN_USER_INFO)
                .get()
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .build();
        OkHttpClient client = httpClient.newBuilder()
                .readTimeout(8, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                item.put("error", "查询失败（HTTP " + response.code() + "）");
                item.put("consoleUrl", QWEN_CONSOLE);
                return item;
            }
            JsonNode data = objectMapper.readTree(body).path("data");
            if (data.isMissingNode() || data.isNull()) {
                item.put("error", "响应里没有账户信息");
                item.put("consoleUrl", QWEN_CONSOLE);
                return item;
            }
            BigDecimal total = decimal(data.path("totalBalance"));
            BigDecimal charge = decimal(data.path("chargeBalance"));
            BigDecimal gift = decimal(data.path("balance"));
            item.put("totalBalance", total);
            item.put("chargeBalance", charge);
            item.put("giftBalance", gift);
            item.put("currency", "CNY");
            item.put("note", "赠送额度有使用期限，以控制台为准");
            item.put("consoleUrl", QWEN_CONSOLE);
            return item;
        } catch (IOException e) {
            log.warn("查询硅基流动余额失败: {}", e.getMessage());
            item.put("error", "查询失败：" + e.getMessage());
            item.put("consoleUrl", QWEN_CONSOLE);
            return item;
        }
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
