package org.example.fqxs.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.repository.AiEngineRepository;
import org.example.fqxs.web.dto.EngineStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * AI 引擎注册表：内置免费渠道与用户自带的 OpenAI 兼容端点统一在这里读。
 *
 * <p>判定链路不再认识具体厂商：取一条记录 → 按其 baseUrl / model / key 调用。
 * 密钥不在表里，由 {@link AiKeyStore} 单独管理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiEngineRegistry {

    /** 型号名只允许字母数字与 . _ / -（Qwen 的 ID 带斜杠），且禁止 .. 片段。 */
    private static final Pattern MODEL_PATTERN =
            Pattern.compile("^(?!.*\\.\\.)[A-Za-z0-9._/-]{1,80}$");

    /** 引擎 id 只允许小写字母、数字与连字符，长度 3–40。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9-]{3,40}$");

    private final AiEngineRepository repository;
    private final AiKeyStore keyStore;

    /** 全部引擎，按熔断/展示顺序。 */
    public List<AiEngine> all() {
        return repository.findAllByOrderByPriorityAsc();
    }

    public Optional<AiEngine> find(String id) {
        return id == null ? Optional.empty() : repository.findById(id);
    }

    /**
     * 取引擎记录，不存在直接报错。
     *
     * @param id 引擎标识
     * @return 记录
     * @throws IllegalArgumentException 未知引擎
     */
    public AiEngine require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("未知引擎: " + id));
    }

    /** 该引擎是否已配置 Key（转发密钥存储，调用方不必同时依赖两个服务）。 */
    public boolean hasKey(String id) {
        return keyStore.hasKey(id);
    }

    /** 引擎是否可用于自动判定：启用 + OpenAI 兼容 + 有 Key。 */
    public boolean usable(String id) {
        return find(id)
                .filter(AiEngine::isEnabled)
                .filter(record -> !record.isSuspended())
                .filter(AiEngine::isOpenAiCompatible)
                .map(record -> keyStore.hasKey(id))
                .orElse(false);
    }

    /** 可用于批量判定的免费引擎：启用 + 未被自动停用 + OpenAI 兼容 + 已配 Key，按 priority。 */
    public List<String> availableFreeEngines() {
        List<String> engines = new ArrayList<>();
        for (AiEngine engine : all()) {
            if (engine.isEnabled() && !engine.isSuspended()
                    && engine.isOpenAiCompatible() && keyStore.hasKey(engine.getId())) {
                engines.add(engine.getId());
            }
        }
        return engines;
    }

    /** 正在自动停用中的引擎：日志页面要显示「为什么被停、什么时候恢复、能否手动解除」。 */
    public List<Map<String, String>> suspendedEngines() {
        List<Map<String, String>> result = new ArrayList<>();
        for (AiEngine engine : all()) {
            if (!engine.isSuspended()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", engine.getId());
            item.put("name", engine.getName());
            item.put("disableUntil", String.valueOf(engine.getDisableUntil()));
            item.put("disableReason", engine.getDisableReason() == null ? "" : engine.getDisableReason());
            item.put("consecutiveFailures", String.valueOf(engine.getConsecutiveFailures()));
            result.add(item);
        }
        return result;
    }

    /** 可用于逐章校验的引擎：运行中的 OpenAI 兼容引擎，都要求已配 Key。 */
    public List<Map<String, String>> availableVerifyEngines() {
        List<Map<String, String>> result = new ArrayList<>();
        for (AiEngine engine : all()) {
            // 逐章校验复用免费判定通道，非 OpenAI 兼容的自定义端点无法按章送判
            if (!engine.isEnabled() || engine.isSuspended() || !engine.isOpenAiCompatible()
                    || !keyStore.hasKey(engine.getId())) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", engine.getId());
            item.put("name", engine.getName());
            item.put("shortName", shortName(engine));
            result.add(item);
        }
        return result;
    }

    /** 面板数据：在原有字段（configured/masked/source/model）之外补上引擎元数据。 */
    public Map<String, EngineStatus> describeAll() {
        Map<String, EngineStatus> result = new LinkedHashMap<>();
        for (AiEngine engine : all()) {
            List<String> models = splitModels(engine.getModelsCsv());
            // 供面板给免费型号打标；未收录的一律不标（宁可不标，不能把付费标成免费）
            List<EngineStatus.ModelOption> modelOptions = new ArrayList<>();
            for (String model : models) {
                modelOptions.add(new EngineStatus.ModelOption(
                        model, FreeModelCatalog.isFree(engine.getId(), model)));
            }
            result.put(engine.getId(), new EngineStatus(
                    engine.getName(),
                    engine.isBuiltin(),
                    engine.isEnabled(),
                    engine.getProtocol(),
                    engine.getBaseUrl(),
                    engine.getPriority(),
                    engine.getRemark() == null ? "" : engine.getRemark(),
                    keyStore.hasKey(engine.getId()),
                    keyStore.masked(engine.getId()),
                    keyStore.sourceOf(engine.getId()),
                    engine.getModel(),
                    modelsOf(engine.getId()),
                    engine.isOpenAiCompatible(),
                    models,
                    modelOptions,
                    engine.isSuspended(),
                    engine.getDisableUntil() == null ? "" : engine.getDisableUntil().toString(),
                    engine.getConsecutiveFailures()));
        }
        return result;
    }

    /**
     * 切换一个引擎的型号：该型号会真正用于之后的判定。
     *
     * @param engine 引擎标识
     * @param model  型号名
     * @throws IllegalArgumentException 引擎不存在、朱雀类不支持选型号、型号名非法
     */
    public void updateModel(String engine, String model) {
        AiEngine record = require(engine);
        if (!record.isOpenAiCompatible()) {
            throw new IllegalArgumentException("该引擎不支持选择模型: " + engine);
        }
        String normalized = model == null ? "" : model.trim();
        if (!MODEL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("模型名不合法（只允许字母、数字与 . _ / -，最长 80 字符）");
        }
        record.setModel(normalized);
        record.setModelPool(null);
        repository.save(record);
        log.info("AI 模型已切换: engine={}, model={}", engine, normalized);
    }

    /**
     * 设置型号池：勾选多个型号后，每次判定随机取一个。
     *
     * @param engine 引擎标识
     * @param models 勾选的型号（去重后写库，第一个作为主型号）
     */
    public void updateModels(String engine, List<String> models) {
        AiEngine record = require(engine);
        if (!record.isOpenAiCompatible()) {
            throw new IllegalArgumentException("该引擎不支持选择模型: " + engine);
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String model : models) {
            String normalized = model == null ? "" : model.trim();
            if (!MODEL_PATTERN.matcher(normalized).matches()) {
                throw new IllegalArgumentException("模型名不合法（只允许字母、数字与 . _ / -，最长 80 字符）");
            }
            unique.add(normalized);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个模型");
        }
        // 型号清单同步并集：勾选的型号若不在清单里，界面会看不到、也没法取消
        LinkedHashSet<String> catalog = new LinkedHashSet<>(splitModels(record.getModelsCsv()));
        catalog.addAll(unique);
        record.setModelsCsv(String.join(",", catalog));
        record.setModelPool(String.join(",", unique));
        record.setModel(unique.iterator().next());
        repository.save(record);
        log.info("AI 型号池已更新: engine={}, models={}", engine, unique);
    }

    /**
     * 把端点拉到的型号并进候选清单：只扩充 models_csv，不改当前型号与型号池。
     *
     * @param engine    引擎标识
     * @param discovered 端点返回的型号
     * @return 新增的型号个数
     */
    public int mergeModels(String engine, List<String> discovered) {
        AiEngine record = require(engine);
        LinkedHashSet<String> merged = new LinkedHashSet<>(splitModels(record.getModelsCsv()));
        int before = merged.size();
        for (String model : discovered) {
            String normalized = model == null ? "" : model.trim();
            if (MODEL_PATTERN.matcher(normalized).matches()) {
                merged.add(normalized);
            }
        }
        if (merged.size() == before) {
            return 0;
        }
        String csv = String.join(",", merged);
        if (csv.length() > 2000) {
            csv = csv.substring(0, csv.lastIndexOf(',', 2000));
        }
        record.setModelsCsv(csv);
        repository.save(record);
        log.info("型号清单已补充: engine={}, 新增 {} 个", engine, merged.size() - before);
        return merged.size() - before;
    }

    /**
     * 当前勾选的型号列表：型号池为空时退化为单型号。
     *
     * @param engine 引擎标识
     * @return 非空型号列表
     */
    public List<String> modelsOf(String engine) {
        AiEngine record = require(engine);
        List<String> pool = splitModels(record.getModelPool());
        return pool.isEmpty() ? List.of(record.getModel()) : pool;
    }

    /** 随机取一个勾选型号（多模型分摊），只有一个时就是它本身。 */
    public String pickModel(String engine) {
        List<String> models = modelsOf(engine);
        return models.get(ThreadLocalRandom.current().nextInt(models.size()));
    }

    /** 主型号（逐章校验这类需要稳定进度的场景使用）。 */
    public String primaryModel(String engine) {
        return modelsOf(engine).get(0);
    }

    /**
     * 新增或更新一个引擎；内置引擎的 id、名称、内置标记与**端点**都不可改（端点锁定的理由见内联注释）。
     *
     * @param incoming 引擎记录（id 为空时自动生成）
     * @return 保存后的记录
     */
    public AiEngine save(AiEngine incoming) {
        String id = incoming.getId() == null ? "" : incoming.getId().trim();
        AiEngine existing = id.isEmpty() ? null : repository.findById(id).orElse(null);
        if (existing == null) {
            if (id.isEmpty()) {
                id = "custom-" + Long.toHexString(System.nanoTime());
            }
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("引擎标识只允许小写字母、数字与连字符（3–40 位）");
            }
            if (incoming.getName() == null || incoming.getName().isBlank()) {
                throw new IllegalArgumentException("引擎名称不能为空");
            }
            incoming.setId(id);
            incoming.setBuiltin(false);
            incoming.setProtocol("openai_chat");
            incoming.setBaseUrl(normalizeBaseUrl(incoming.getBaseUrl()));
            requireModel(incoming.getModel());
            if (incoming.getModelsCsv() == null) {
                incoming.setModelsCsv(incoming.getModel());
            }
            log.info("新增 AI 引擎: id={}, baseUrl={}", id, incoming.getBaseUrl());
            return repository.save(incoming);
        }

        if (existing.isBuiltin()) {
            // 内置引擎允许改型号/启停/优先级/备注，但**端点锁死**（安全测试报告 M6）：
            // 端点可改就等于「任何能调这个接口的人都能让服务器把真实 Key 当 Bearer 发到自己的地址」，
            // 这是 H1 攻击链的第三步，登录门只挡住了"匿名的人"，挡不住"已经进来的人"。
            // 要换网关请「新增自定义引擎」——那条路有独立的 key.<id>，不必动内置端点。
            String incomingUrl = incoming.getBaseUrl();
            if (incomingUrl != null && !incomingUrl.isBlank()) {
                String normalized = normalizeBaseUrl(incomingUrl);
                if (!normalized.equals(safeNormalize(existing.getBaseUrl()))) {
                    throw new IllegalArgumentException("内置引擎的端点不可修改（要换网关请新增一个自定义引擎）");
                }
            }
            existing.setEnabled(incoming.isEnabled());
            existing.setPriority(incoming.getPriority());
            existing.setRemark(incoming.getRemark());
            if (incoming.getModelsCsv() != null && !incoming.getModelsCsv().isBlank()) {
                existing.setModelsCsv(incoming.getModelsCsv());
            }
            if (incoming.getModel() != null && !incoming.getModel().isBlank()) {
                requireModel(incoming.getModel());
                existing.setModel(incoming.getModel());
            }
            return repository.save(existing);
        }

        incoming.setBuiltin(false);
        incoming.setProtocol("openai_chat");
        incoming.setBaseUrl(normalizeBaseUrl(incoming.getBaseUrl()));
        if (incoming.getName() == null || incoming.getName().isBlank()) {
            incoming.setName(existing.getName());
        }
        requireModel(incoming.getModel());
        return repository.save(incoming);
    }

    /**
     * 删除一个自定义引擎；内置引擎只允许停用。
     *
     * @param id 引擎标识
     */
    public void delete(String id) {
        AiEngine record = require(id);
        if (record.isBuiltin()) {
            throw new IllegalArgumentException("内置引擎不能删除，可以停用");
        }
        repository.delete(record);
        log.info("已删除 AI 引擎: id={}", id);
    }

    /**
     * 端点归一：裸主机补 /v1/chat/completions；以 /vN 结尾补 /chat/completions。
     *
     * @param raw 用户输入
     * @return 归一后的完整 chat 端点
     * @throws IllegalArgumentException scheme/主办地址不合法
     */
    public static String normalizeBaseUrl(String raw) {
        return AiEndpointNormalizer.normalize(raw);
    }

    /**
     * 归一化已存库的端点；库里是历史脏值时退回原串比较。
     *
     * <p>用途只有一个：判断「这次提交的端点跟库里是不是同一个」，所以不能因为
     * 一条早期没走校验的旧值让整个保存流程抛异常。</p>
     */
    private static String safeNormalize(String stored) {
        if (stored == null) {
            return "";
        }
        try {
            return normalizeBaseUrl(stored);
        } catch (RuntimeException ignored) {
            return stored.trim();
        }
    }

    /** 型号清单解析：去空白、去空项。 */
    private static List<String> splitModels(String modelsCsv) {
        List<String> models = new ArrayList<>();
        if (modelsCsv == null || modelsCsv.isBlank()) {
            return models;
        }
        for (String piece : modelsCsv.split(",")) {
            String model = piece.trim();
            if (!model.isEmpty()) {
                models.add(model);
            }
        }
        return models;
    }

    private static void requireModel(String model) {
        if (model == null || !MODEL_PATTERN.matcher(model.trim()).matches()) {
            throw new IllegalArgumentException("模型名不合法（只允许字母、数字与 . _ / -，最长 80 字符）");
        }
    }

    /** 按钮短名：内置按前缀归类，自定义取名称前 2 个字符。 */
    private static String shortName(AiEngine engine) {
        String id = engine.getId();
        if (id.startsWith("glm")) {
            return "GLM";
        }
        if (id.startsWith("qianfan")) {
            return "ERNIE";
        }
        if (id.startsWith("qwen")) {
            return "QW";
        }
        String name = engine.getName() == null ? id : engine.getName();
        return name.length() <= 2 ? name : name.substring(0, 2);
    }
}
