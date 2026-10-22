package org.example.fqxs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 文判定配置。
 *
 * <p>各引擎的密钥都走环境变量或本地文件，配置文件里不落明文。引擎清单已迁入
 * {@code ai_engine} 表（见 {@code AiEngineRegistry}）；这里的 url/model/key 字段
 * 只作为内置引擎首次播种的素材与环境变量入口，运行时真值以数据库为准。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.detect")
public class AiDetectConfig {

    /** 总开关：关闭后判定接口直接拒绝，避免误触发外部调用。 */
    private boolean enabled = true;

    /**
     * 默认取样章数：不带策略时取前 N 章。
     *
     * <p>2026-10-22 起默认策略改为"前 N 章"（原为"中段+最新"）：未登录状态只有
     * 前 10 章是完整正文，中段与最新段大面积落在 150–180 字试读上，样本质量反而差。</p>
     */
    private int sampleHeadChapters = 10;

    /** 免费初筛中段取样章数：跳过开头铺垫章，取书中间位置的若干章送判（仅显式中段策略使用）。 */
    private int sampleMidChapters = 2;

    /** 免费初筛最新段取样章数：取全书最后若干章（最新更新）送判。 */
    private int sampleRecentChapters = 2;

    /** 单章正文低于此字数视为试读片段（VIP 章节未登录只给开头），不参与取样。 */
    private int minChapterChars = 500;

    /** 样本总字数低于此值直接报错：拿试读硬判会得出不可靠结论。 */
    private int minSampleChars = 1500;

    /**
     * 可读章节上限：未接入会员正文通道时，番茄未登录只给前 10 章完整正文
     * （第 11 章起实测 150–180 字试读）。取样候选与回退扫描都钳在这个区间内，
     * 免得把上游请求浪费在必然只有试读的深章上。0 或负数 = 不限，
     * 将来接入会员通道后用它做全书中段/最新抽查。
     */
    private int freeHeadChapters = 10;

    /**
     * 第三方正文通道地址：补官方未登录取不到的会员章节（第 11 章起）。
     * 走的是别人的共享线路，302 目标按天变，所以只填源站域名、让它自己跟随重定向。
     */
    private String thirdPartyBaseUrl = "https://fq-book.netsite.cc";

    /** 是否允许使用第三方正文通道做全书抽查。关掉就退回「只用可读区」的降级形态。 */
    private boolean thirdPartyEnabled = true;

    /** 第三方通道结构性失败后的冷却秒数：期间不再打它，免得每本书都白等一次。 */
    private int thirdPartyCooldownSeconds = 300;

    /** 同一引擎连续失败多少次就自动停用（判定成功会清零计数，所以这里数的是"连着"）。 */
    private int faultSuspendThreshold = 3;

    /** 自动停用时长（小时）：免费渠道的限流与故障常常几小时内恢复，两天够避开一波。 */
    private int faultSuspendHours = 48;

    /** 单本取样最多请求的章节数（策略章节 + 回退扫描合计），防止一次判定打太多上游请求。 */
    private int sampleRequestLimit = 12;

    /**
     * 单本送判的最大字符数，超出截断。
     *
     * <p>2026-10-22 从 8000 提到 24000：取样改成「前 10 章 / 末 10 章 / 两段各 5 章」后，
     * 8000 字会在第 4 章就截断，"前 10 章"名不副实。24000 字约等于 10 章 × 2400 字，
     * 仍在千帆 32k 上下文的余量内（中文约 1 字 1 token，加提示词不到 25k）。</p>
     */
    private int maxCharsPerBook = 24000;

    /** 免费引擎判定为 AI 的概率阈值（含）。 */
    private int freeAiThreshold = 70;

    /** 免费引擎判定为“疑似 AI”的概率阈值（含），低于它即判为非 AI。 */
    private int freeSuspectThreshold = 50;

    /** 逐章校验最多抽查的章节数：每种型号按取样条件各查一批，查完即可换型号或结束。 */
    private int verifyMaxChapters = 10;

    /** 批量初筛未指定 limit 时的默认本数（页面加载不再自动初筛）。 */
    private int autoSweepLimit = 5;

    /** 单次批量初筛允许的最大本数，防止一次点击打满上游与免费额度。 */
    private int sweepLimitMax = 30;

    /**
     * 每日模型调用次数上限（安全测试报告 M1）：0 或负数表示不限。
     *
     * <p>计数口径是 {@code ai_usage} 里当天的行数（一次真实出网调用一行），
     * 由 {@code AiDailyQuota} 在 {@code AiDetectClient} 每次请求前把关。
     * 它挡的是「循环点按钮/脚本刷接口把免费额度烧完」，不是精确计费——
     * 计数在调用前读、用量在调用后异步落库，所以最后几次可能略超上限。</p>
     */
    private int dailyCallLimit = 500;

    /** 两次上游请求之间的间隔，避免连续抓章节触发风控。 */
    private long requestIntervalMillis = 800;

    /** 批量初筛的并发路数：只有模型调用并行；上游章节请求仍走全局节流器，总速率与串行一致。 */
    private int sweepConcurrency = 3;

    /** 章节正文 LRU 缓存条数：初筛后再逐章校验、换引擎重判时不再重复抓上游。 */
    private int chapterCacheSize = 300;

    /**
     * 是否把官方可读区（前 10 章）的正文落库（{@code novel_chapter}）。
     *
     * <p>默认开：默认取样就是前 10 章，同一本书换引擎重判、批量初筛、逐章校验都会反复读同一批章节，
     * 落库后第二次起零上游请求。关掉则退回纯内存 LRU——排查「库里的正文不对」时用。</p>
     */
    private boolean chapterStoreEnabled = true;

    /**
     * 提示词选取策略：{@code random} 每次从启用池内随机取一条（默认），
     * {@code fixed} 固定用 {@link #defaultPrompt}。想让结论可复现时改成 fixed。
     */
    private String promptStrategy = "random";

    /** 固定策略下使用的提示词变体 id；随机策略下池为空时也会退回它。 */
    private String defaultPrompt = "p1-total";

    /** 检测类接口的读超时：模型判定明显慢于榜单抓取。 */
    private long timeoutSeconds = 90;

    /** GLM 对话接口（OpenAI 兼容）。 */
    private String glmUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    /** GLM 模型标识，默认用免费档的 glm-4-flash。 */
    private String glmModel = "glm-4-flash";

    /** GLM API Key；为空表示未配置，该引擎会被禁用而不是报错。 */
    private String glmApiKey = "";

    /** 百度千帆对话接口（OpenAI 兼容 V2）。 */
    private String qianfanUrl = "https://qianfan.baidubce.com/v2/chat/completions";

    /** 千帆模型：ERNIE-Speed 系（需在千帆控制台开通在线服务后才可用）。 */
    private String qianfanModel = "ernie-4.5-turbo-32k";

    /** 千帆 API Key（bce-v3/ALTAK-...）；为空表示未配置。 */
    private String qianfanApiKey = "";

    /** 硅基流动对话接口（OpenAI 兼容），第二免费引擎。 */
    private String qwenUrl = "https://api.siliconflow.cn/v1/chat/completions";

    /** 硅基流动免费档模型：7B 级别，适合做粗筛。 */
    private String qwenModel = "Qwen/Qwen2.5-7B-Instruct";

    /** 硅基流动 API Key；为空表示未配置，该引擎会被禁用而不是报错。 */
    private String qwenApiKey = "";

    /** 内置引擎的固定标识；用户自带的引擎 id 由注册表生成，历史结论按 engine 关联。 */
    public static final String ENGINE_GLM = "glm-4-flash";
    public static final String ENGINE_QIANFAN = "qianfan-ernie-speed";
    public static final String ENGINE_QWEN = "qwen2.5-7b";
}
