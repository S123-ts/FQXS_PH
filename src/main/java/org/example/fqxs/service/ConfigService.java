package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.constant.CategoryCatalog;
import org.example.fqxs.entity.AppConfig;
import org.example.fqxs.repository.AppConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * H2 里的运行时配置（app_config 表）：榜单类型与展示分类。
 * 读请求走内存缓存不打库；写入后使缓存失效，下次读取重建。
 * 默认值只在本键不存在时播种（StartupDataInitializer 调 seedDefaults），用户配置永远优先。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    public static final String KEY_RANK_TYPE = "rank_type";
    public static final String KEY_DISPLAY_CATEGORIES = "display_categories";
    /** 拉取频道：只拉男频 / 只拉女频 / 全拉。只影响「批量拉取抓哪些分类」，不影响页面展示。 */
    public static final String KEY_FETCH_GENDER = "fetch_gender";

    public static final String RANK_TYPE_READ = "read";
    public static final String RANK_TYPE_NEW = "new";
    public static final String DEFAULT_RANK_TYPE = RANK_TYPE_READ;

    public static final String FETCH_ALL = "all";
    public static final String FETCH_MALE = "male";
    public static final String FETCH_FEMALE = "female";
    public static final java.util.Set<String> FETCH_GENDERS =
            java.util.Set.of(FETCH_ALL, FETCH_MALE, FETCH_FEMALE);

    /** rankMold 2=阅读榜，1=新书榜；与 gender、category_id 共同决定抓取的榜单。 */
    public static final Map<String, Integer> RANK_MOLDS =
            Map.of(RANK_TYPE_READ, 2, RANK_TYPE_NEW, 1);

    /** 与重构前页面默认展示的 10 个男频分类保持一致，升级后列表不变不惊吓用户。 */
    public static final String DEFAULT_DISPLAY_CATEGORIES = String.join(",",
            "M_WESTERN_FANTASY", "M_EASTERN_XIANXIA", "M_SCI_FI", "M_URBAN_HIGH_WU",
            "M_HISTORY_ANCIENT", "M_URBAN_FARMING", "M_TRADITIONAL_XUANHUAN",
            "M_HISTORY_BRAIN", "M_XUANHUAN_BRAIN", "M_GAME_SPORTS");

    private final AppConfigRepository appConfigRepository;

    /**
     * 首次播种用的拉取频道。仓库版默认 {@code all}（男女频都拉）；
     * 自用包由 {@code scripts/make-selfuse-jar.py} 把 {@code app.default-fetch-gender} 改成 {@code male}。
     * 只在本键还不存在时生效，之后页面上改过就以库里的值为准。
     */
    @Value("${app.default-fetch-gender:all}")
    private String defaultFetchGender;

    /** 缓存快照；null 表示待加载（启动后或写失效后）。 */
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public record Snapshot(String rankType, String fetchGender, List<String> displayCategoryCodes) {
    }

    public String getActiveRankType() {
        return snapshot().rankType();
    }

    /** 榜单寻址参数：read→2（阅读榜），new→1（新书榜）。 */
    public int getActiveRankMold() {
        return RANK_MOLDS.getOrDefault(getActiveRankType(), RANK_MOLDS.get(DEFAULT_RANK_TYPE));
    }

    /** 当前拉取频道：all / male / female。 */
    public String getFetchGender() {
        return snapshot().fetchGender();
    }

    /**
     * 该分类要不要参与批量拉取。
     *
     * <p>只收窄「一键获取」和每日 01:00 全量这两条批量路径：单点某个分类拉取照旧，
     * 用户点哪个拉哪个。这样自用只拉男频时，女频的存量数据不会被覆盖也不会被掉榜清理。</p>
     */
    public boolean shouldFetch(CategoryCatalog category) {
        int filter = genderCodeOf(getFetchGender());
        return filter < 0 || category.getGender() == filter;
    }

    /** 原样返回存储的 code 列表（含目录里已不存在的码，供 GET /config 排查）。 */
    public List<String> getDisplayCategoryCodes() {
        return snapshot().displayCategoryCodes();
    }

    /** 存储的 code 映射回目录条目，按目录顺序输出；目录里已不存在的码跳过。 */
    public List<CategoryCatalog> getDisplayedCategories() {
        Set<String> codes = new LinkedHashSet<>(getDisplayCategoryCodes());
        return Arrays.stream(CategoryCatalog.values())
                .filter(category -> codes.contains(category.getCode()))
                .toList();
    }

    /** 仅当键不存在时播种默认值；已有配置（含用户清空的空串）一律不动。 */
    @Transactional
    public void seedDefaults() {
        seedIfAbsent(KEY_RANK_TYPE, DEFAULT_RANK_TYPE);
        seedIfAbsent(KEY_DISPLAY_CATEGORIES, DEFAULT_DISPLAY_CATEGORIES);
        seedIfAbsent(KEY_FETCH_GENDER, normalizeFetchGender(defaultFetchGender));
        refreshCacheAfterCommit();
    }

    /**
     * 更新配置：参数 null 表示不改；displayCategories 传空列表 = 清空展示分类。
     * 校验失败抛 IllegalArgumentException（由 GlobalExceptionHandler 归为 400）。
     */
    @Transactional
    public void updateConfig(String rankType, List<String> displayCategories) {
        updateConfig(rankType, displayCategories, null);
    }

    /**
     * 更新配置（带拉取频道）。三个参数各自 null 表示不动。
     *
     * @param fetchGender all / male / female；非法值直接拒绝，不回退成 all——
     *                    静默回退会让用户以为「只拉男频」生效了，实际把女频也拉了
     */
    @Transactional
    public void updateConfig(String rankType, List<String> displayCategories, String fetchGender) {
        List<String> codes = null;
        if (displayCategories != null) {
            codes = displayCategories.stream()
                    .map(code -> code == null ? "" : code.trim())
                    .toList();
            validateCodes(codes);
        }
        if (rankType != null) {
            // 取值域由 ConfigUpdateRequest 的 @Pattern 钉住；这里只兜 service 被直接调用时的脏值
            appConfigRepository.save(new AppConfig(KEY_RANK_TYPE,
                    RANK_MOLDS.containsKey(rankType) ? rankType : DEFAULT_RANK_TYPE));
        }
        if (fetchGender != null) {
            if (!FETCH_GENDERS.contains(fetchGender)) {
                throw new IllegalArgumentException("fetchGender 只支持 all / male / female: " + fetchGender);
            }
            appConfigRepository.save(new AppConfig(KEY_FETCH_GENDER, fetchGender));
        }
        if (codes != null) {
            appConfigRepository.save(new AppConfig(KEY_DISPLAY_CATEGORIES, String.join(",", codes)));
        }
        refreshCacheAfterCommit();
        log.info("配置已更新: rankType={}, fetchGender={}, displayCategories={}",
                rankType, fetchGender, codes);
    }

    /** 缓存置空即可：下次读取重建（写后失效重建，读路径始终走缓存）。 */
    public void refreshCache() {
        cache.set(null);
    }

    /**
     * 缓存失效做两次：立即失效让单测（事务不提交）也能看到本连接的写入；
     * 提交后再失效一次，关掉“提交前失效被并发读者用旧提交值重建并永久缓存”的窗口。
     * 无事务上下文时第二次是空操作。
     */
    private void refreshCacheAfterCommit() {
        refreshCache();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refreshCache();
                }
            });
        }
    }

    private void validateCodes(List<String> codes) {
        for (String code : codes) {
            if (code.isEmpty() || CategoryCatalog.fromCode(code) == null) {
                throw new IllegalArgumentException("displayCategories 含未知分类 code: " + code);
            }
        }
        if (new LinkedHashSet<>(codes).size() != codes.size()) {
            throw new IllegalArgumentException("displayCategories 存在重复分类 code");
        }
    }

    private void seedIfAbsent(String key, String defaultValue) {
        if (appConfigRepository.existsById(key)) {
            return;
        }
        appConfigRepository.save(new AppConfig(key, defaultValue));
        log.info("播种默认配置: {}={}", key, defaultValue);
    }

    private Snapshot snapshot() {
        Snapshot current = cache.get();
        if (current != null) {
            return current;
        }
        Snapshot loaded = loadSnapshot();
        cache.set(loaded);
        return loaded;
    }

    private Snapshot loadSnapshot() {
        Map<String, String> values = appConfigRepository.findAll().stream()
                .collect(Collectors.toMap(AppConfig::getCfgKey, AppConfig::getCfgValue));
        String rankType = values.getOrDefault(KEY_RANK_TYPE, DEFAULT_RANK_TYPE);
        if (!RANK_MOLDS.containsKey(rankType)) {
            // 库里出现脏值时退回默认，而不是让所有抓取接口一起 500
            log.warn("app_config 里的 rank_type 不合法: {}，退回 {}", rankType, DEFAULT_RANK_TYPE);
            rankType = DEFAULT_RANK_TYPE;
        }
        String fetchGender = values.getOrDefault(KEY_FETCH_GENDER, normalizeFetchGender(defaultFetchGender));
        if (!FETCH_GENDERS.contains(fetchGender)) {
            log.warn("app_config 里的 fetch_gender 不合法: {}，退回 {}", fetchGender, FETCH_ALL);
            fetchGender = FETCH_ALL;
        }
        return new Snapshot(rankType, fetchGender, splitCodes(values.get(KEY_DISPLAY_CATEGORIES)));
    }

    /** 频道过滤码：all→-1（不过滤），male→1，female→0；供 SQL 参数直接使用。 */
    public static int genderCodeOf(String fetchGender) {
        return switch (fetchGender == null ? FETCH_ALL : fetchGender) {
            case FETCH_MALE -> 1;
            case FETCH_FEMALE -> 0;
            default -> -1;
        };
    }

    /** 把配置里的默认值收敛到三选一；写错（比如写成 m / 男）按 all 处理，不让启动失败。 */
    private static String normalizeFetchGender(String value) {
        return value != null && FETCH_GENDERS.contains(value.trim()) ? value.trim() : FETCH_ALL;
    }

    private List<String> splitCodes(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList();
    }
}
