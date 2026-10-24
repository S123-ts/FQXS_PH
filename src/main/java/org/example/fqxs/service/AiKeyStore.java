package org.example.fqxs.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.config.AiDetectConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * AI 判定密钥的本地存储与热更新。
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li><b>只落本地文件</b>：写在 {@code data/ai-keys.properties}，与 H2 文件库同目录，
 *       该目录已被 .gitignore 忽略，永远不会进版本库；</li>
 *   <li><b>不回显明文</b>：查询接口只返回掩码（前 4 + 后 4），日志不打印 Key；</li>
 *   <li><b>环境变量优先</b>：启动时若 {@code GLM_API_KEY} 等环境变量已设置，
 *       以环境变量为准，不用文件覆盖；只有环境变量为空时才用文件里的值。</li>
 * </ol>
 *
 * <p>引擎标识不再限于内置三家：内置沿用 glm/qwen/qianfan/zhuque 四个属性名，
 * 用户自带的引擎用 {@code key.<引擎id>}，与内置命名空间隔离。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiKeyStore {

    /** 密钥文件：与数据库同目录，data/ 在 .gitignore 里。可用 AI_KEYS_FILE 覆盖（测试隔离用）。 */
    private static final Path KEY_FILE = Path.of(
            System.getenv().getOrDefault("AI_KEYS_FILE", "data/ai-keys.properties"));

    /** 内置引擎使用的属性名（历史兼容：改这里会让已保存的 Key 失效）。 */
    private static final Map<String, String> BUILTIN_PROP = Map.of(
            AiDetectConfig.ENGINE_GLM, "glm",
            AiDetectConfig.ENGINE_QIANFAN, "qianfan",
            AiDetectConfig.ENGINE_QWEN, "qwen");

    /** 自定义引擎 Key 的属性名前缀。 */
    private static final String CUSTOM_PREFIX = "key.";

    private final AiDetectConfig aiDetectConfig;

    /** 自定义引擎的 Key（内置引擎直接读 AiDetectConfig 字段）。判定线程读、保存线程写，必须并发安全。 */
    private final Map<String, String> customKeys = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 启动时把文件里的 Key 补进配置/内存：环境变量已设置的不覆盖。
     * 模型属性（glm-model/qwen-model）只在首次播种时作为迁移素材读取。
     */
    @PostConstruct
    public void loadFromFile() {
        Properties props = readFile();
        for (Map.Entry<String, String> entry : BUILTIN_PROP.entrySet()) {
            String engine = entry.getKey();
            String fileKey = props.getProperty(entry.getValue(), "").trim();
            if (hasKey(engine)) {
                continue;
            }
            if (!fileKey.isEmpty()) {
                applyKey(engine, fileKey);
                log.info("从 data/ai-keys.properties 载入 {} 的密钥（已掩码）", engine);
            }
        }
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(CUSTOM_PREFIX)) {
                continue;
            }
            String engine = name.substring(CUSTOM_PREFIX.length());
            String value = props.getProperty(name, "").trim();
            if (!engine.isEmpty() && !value.isEmpty()) {
                customKeys.put(engine, value);
            }
        }
        // 旧版把型号存在 properties；现在型号以 ai_engine 表为准，
        // 这里把旧值读进配置，仅供首次播种内置引擎时作为种子（播种后不再回写）。
        migrateModel(props, "glm-model", aiDetectConfig::setGlmModel);
        migrateModel(props, "qwen-model", aiDetectConfig::setQwenModel);
    }

    private void migrateModel(Properties props, String prop, java.util.function.Consumer<String> setter) {
        String saved = props.getProperty(prop, "").trim();
        if (!saved.isEmpty()) {
            setter.accept(saved);
        }
    }

    /**
     * 保存或清除一个引擎的密钥：立即生效并持久化到本地文件。
     *
     * @param engine 引擎标识（内置或用户自带）
     * @param key    密钥；空串表示清除
     * @throws IllegalStateException 文件写入失败（内存已更新，重启后会丢）
     */
    public void save(String engine, String key) {
        // 内置 id 直接放行：qwen2.5-7b 含点，不满足自定义引擎的字符规则
        if (engine == null || (!BUILTIN_PROP.containsKey(engine)
                && !AiEngineRegistry.ID_PATTERN.matcher(engine).matches())) {
            throw new IllegalArgumentException("引擎标识不合法: " + engine);
        }
        String normalized = key == null ? "" : key.trim();
        if (BUILTIN_PROP.containsKey(engine)) {
            applyKey(engine, normalized);
        } else {
            customKeys.put(engine, normalized);
        }

        Properties props = readFile();
        String prop = propFor(engine);
        if (normalized.isEmpty()) {
            props.remove(prop);
        } else {
            props.setProperty(prop, normalized);
        }
        writeFile(props);
        log.info("AI 密钥已{}: engine={}（内容不落日志）", normalized.isEmpty() ? "清除" : "保存", engine);
    }

    /**
     * 读取明文 Key（只在服务端使用，绝不直接返回给页面）。
     *
     * @param engine 引擎标识
     * @return 密钥；未配置返回空串
     */
    public String currentKey(String engine) {
        if (engine == null) {
            return "";
        }
        String value = switch (engine) {
            case AiDetectConfig.ENGINE_GLM -> aiDetectConfig.getGlmApiKey();
            case AiDetectConfig.ENGINE_QIANFAN -> aiDetectConfig.getQianfanApiKey();
            case AiDetectConfig.ENGINE_QWEN -> aiDetectConfig.getQwenApiKey();
            default -> customKeys.get(engine);
        };
        return value == null ? "" : value;
    }

    /** 该引擎是否已配置 Key。 */
    public boolean hasKey(String engine) {
        return !currentKey(engine).isBlank();
    }

    /** 页面展示用的掩码。 */
    public String masked(String engine) {
        String key = currentKey(engine);
        return key.isBlank() ? "" : mask(key);
    }

    /** Key 的来源：env（内置且被环境变量覆盖）/ file（已保存）/ none。 */
    public String sourceOf(String engine) {
        if (!hasKey(engine)) {
            return "none";
        }
        return BUILTIN_PROP.containsKey(engine) ? "env-or-file" : "file";
    }

    /** 密钥文件路径，文档与错误提示里引用，避免两处写死。 */
    public static String fileLocation() {
        return KEY_FILE.toAbsolutePath().normalize().toString();
    }

    private String propFor(String engine) {
        String builtin = BUILTIN_PROP.get(engine);
        return builtin != null ? builtin : CUSTOM_PREFIX + engine;
    }

    /** 掩码：只露前 4 位与后 4 位，中间固定 4 个星号（长度信息也不泄露）。 */
    private String mask(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void applyKey(String engine, String key) {
        switch (engine) {
            case AiDetectConfig.ENGINE_GLM -> aiDetectConfig.setGlmApiKey(key);
            case AiDetectConfig.ENGINE_QIANFAN -> aiDetectConfig.setQianfanApiKey(key);
            case AiDetectConfig.ENGINE_QWEN -> aiDetectConfig.setQwenApiKey(key);
            default -> throw new IllegalArgumentException("不支持的引擎: " + engine);
        }
    }

    /** 读文件；不存在或损坏都按空配置处理（密钥缺失只影响判定，不该阻断启动）。 */
    private Properties readFile() {
        Properties props = new Properties();
        if (!Files.isRegularFile(KEY_FILE)) {
            return props;
        }
        try (Reader reader = Files.newBufferedReader(KEY_FILE, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            log.warn("读取 {} 失败，按未配置处理: {}", KEY_FILE, e.getMessage());
        }
        return props;
    }

    /** 原子写：先写临时文件再替换，避免写一半断电留下坏文件。 */
    private void writeFile(Properties props) {
        try {
            Path parent = KEY_FILE.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = KEY_FILE.resolveSibling(KEY_FILE.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(writer, "FQXS AI keys - local only, never commit (data/ is gitignored)");
            }
            Files.move(tmp, KEY_FILE, StandardCopyOption.REPLACE_EXISTING);
            restrictPermissions();
        } catch (IOException e) {
            throw new IllegalStateException("密钥已生效，但写入 " + KEY_FILE + " 失败（重启后会丢失）: "
                    + e.getMessage(), e);
        }
    }

    /** 类 Unix 上收紧到 600：Windows 没有等价的简单做法，失败忽略。 */
    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(KEY_FILE,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 走不到这里，属正常
        }
    }
}
