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
     * <p>默认策略为"前 N 章"（原为"中段+最新"）：未登录状态只有
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
     * （第 11 章起为 150–180 字试读）。取样候选与回退扫描都钳在这个区间内，
     * 避免把上游请求浪费在必然只有试读的深章上。0 或负数 = 不限，
     * 将来接入会员通道后用它做全书中段/最新抽查。
     */
    private int freeHeadChapters = 10;

    /**
     * 第三方线路清单（逗号分隔，按顺序优先）：会员正文 {@code /content}、详情 {@code /info}
     * 与目录 {@code /catalog} 三条链路共用这一组地址。
     *
     * <p>走的是别人的共享线路，302 目标按天变，所以只填源站域名、让它自己跟随重定向。
     * 某一条挂了只冷却它自己，取样与目录会自动改用清单里的下一条。</p>
     */
    private List<String> thirdPartyBaseUrls = new ArrayList<>(List.of("https://fq-book.netsite.cc"));

    /** 第三方线路整体是否可用；关掉就退回「只用官方可读区」的降级形态。 */
    private boolean thirdPartyEnabled = true;

    /** 单条线路结构性失败后的冷却秒数：期间不再打它，免得每本书都白等一次。 */
    private int thirdPartyCooldownSeconds = 300;

    /** 补作者信息/评分：只取当前榜单热度前 N 本，避免把共享线路打满。 */
    private int bookMetaTopRank = 50;

    /** 单次最多请求几本（第三方 /info 约 19KB/本，线路很小）。 */
    private int bookMetaBatchMax = 10;

    /** 两次 /info 之间的固定间隔：不并发、不连打。 */
    private long bookMetaIntervalMillis = 2000;

    /** 评分刷新周期（天）。 */
    private int ratingRefreshDays = 5;

    /**
     * 启动后台补齐开关：应用起来后开一个守护线程，按 {@link #bookMetaBackfillPerHour}
     * 的节拍（默认 60 = 一分钟一本）把全库缺性别/缺评分的书补完。
     * 关掉只留页面上的手动「补作者信息」。
     */
    private boolean bookMetaBackfillEnabled = true;

    /**
     * 后台补齐节拍：每小时补几本（默认 60 = 一分钟一本）。
     *
     * <p>这一个数同时决定两件事——每本之间的间隔 = 3600000 / perHour；每一轮推进的本数 = perHour。
     * 一轮正好铺满一小时，轮末重取待补清单，新上榜的书与评分到期的书靠这次重取轮进来。</p>
     *
     * <p>2026-10-25 晚按用户要求从批次制（{@code book-meta-backfill-batch-size} 随机 5 本 +
     * {@code book-meta-backfill-pause-minutes} 停 3 分钟）改回线性节拍。</p>
     */
    private int bookMetaBackfillPerHour = 60;

    /**
     * 后台逐章校验开关：应用起来后开一个守护线程，把所有书的前 N 章用每个可用引擎各跑一遍——
     * 只跑同模型的「家族代表」型号（包装差异只留一个，见 ModelFamily）。
     * 节拍见 {@link #aiVerifyBackfillPerHour}。
     */
    private boolean aiVerifyBackfillEnabled = true;

    /**
     * 后台逐章校验每个引擎每小时推进几本（默认 6 = 10 分钟一本）。一轮把每个可用引擎各推一本，
     * 所以三家一轮 3 次模型调用；免费渠道按最慢那条的承受能力算，不按家数放大。
     */
    private int aiVerifyBackfillPerHour = 6;

    /** 同一引擎连续失败多少次就自动停用（判定成功会清零计数，所以这里数的是"连着"）。 */
    private int faultSuspendThreshold = 3;

    /** 自动停用时长（小时）：报错到阈值就歇这么久再试，免费渠道的限流通常一小时内恢复。 */
    private int faultSuspendHours = 1;

    /**
     * 上游明确报「余额不足 / 欠费」时，是否自动把该型号从型号池里取消勾选。
     *
     * <p>与限流的区别在这类错误重试多少次都不会好转（智谱 1113 余额不足、openai
     * {@code insufficient_quota}、HTTP 402），留在池里只会把整个引擎撞到停用；而限流类 429
     * （访问量过大、每日上限）会自行恢复，不能一并摘掉，所以只认余额/计费特征。
     * 只动勾选集、不动候选清单：充值后可以在面板上重新勾回来。</p>
     */
    private boolean dropQuotaModels = true;

    /** 单本取样最多请求的章节数（策略章节 + 回退扫描合计），防止一次判定打太多上游请求。 */
    private int sampleRequestLimit = 12;

    /**
     * 单本送判的最大字符数，超出截断。
     *
     * <p>取值 24000：取样改成「前 10 章 / 末 10 章 / 两段各 5 章」后，
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
     * 每日模型调用次数上限：0 或负数表示不限。
     *
     * <p>计数口径是 {@code ai_usage} 里当天的行数（一次真实出网调用一行），
     * 由 {@code AiDailyQuota} 在 {@code AiDetectClient} 每次请求前把关。
     * 它限制的是循环点击按钮或脚本刷接口造成的额度消耗，不承担精确计费——
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
