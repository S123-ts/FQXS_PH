package org.example.fqxs.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fqxs.client.AiDetectClient;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.model.AiEngineEnabledRequest;
import org.example.fqxs.model.AiEngineRequest;
import org.example.fqxs.model.AiEngineTestRequest;
import org.example.fqxs.model.AiModelUpdateRequest;
import org.example.fqxs.service.AiEngineRegistry;
import org.example.fqxs.web.ApiResponse;
import org.example.fqxs.web.dto.EngineStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 引擎管理接口：注册表查询、增删改、型号切换与连通性测试。
 *
 * <p>所有写操作受 {@code X-Rank-Token} 保护（与 /api/ai/** 其余 POST 同一套）。</p>
 */
@Slf4j
@Tag(name = "AI 引擎")
@RestController
@RequestMapping(value = "/api/ai", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class AiEngineController {

    private final AiEngineRegistry aiEngineRegistry;

    private final AiDetectClient aiDetectClient;

    /**
     * 切换免费引擎实际调用的模型。
     *
     * @param request 引擎标识与型号（或型号池）
     * @return 保存后的全部引擎状态
     */
    @Operation(summary = "切换引擎型号或型号池")
    @PostMapping("/model")
    public ApiResponse<Map<String, EngineStatus>> saveModel(@Valid @RequestBody AiModelUpdateRequest request) {
        log.info("切换 AI 模型: engine={}, model={}, models={}",
                request.engine(), request.model(), request.models());
        if (request.models() != null) {
            aiEngineRegistry.updateModels(request.engine(), request.models());
            String message = request.models().isEmpty()
                    ? "已清空型号池，判定改用当前型号 " + aiEngineRegistry.primaryModel(request.engine())
                    : "已启用 " + request.models().size() + " 个型号，判定时随机调用";
            return ApiResponse.data(message, aiEngineRegistry.describeAll());
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new IllegalArgumentException("model 与 models 至少填一个");
        }
        aiEngineRegistry.updateModel(request.engine(), request.model());
        return ApiResponse.data("已切换为 " + request.model() + "，之后的判定使用该型号",
                aiEngineRegistry.describeAll());
    }

    /** 全部引擎（内置 + 用户自带）的配置状态，面板据此渲染。 */
    @GetMapping("/engines")
    public ApiResponse<Map<String, EngineStatus>> engines() {
        return ApiResponse.data("success", aiEngineRegistry.describeAll());
    }

    /**
     * 新增或更新一个引擎。内置引擎的身份（id/名称/内置标记）与端点都不可改，只可改型号、启停与备注；
     * 要换网关请新增一个自定义引擎。
     *
     * @param request 引擎配置；新增时 id 留空
     * @return 保存后的全部引擎状态
     */
    @PostMapping("/engines")
    public ApiResponse<Map<String, EngineStatus>> saveEngine(@Valid @RequestBody AiEngineRequest request) {
        AiEngine engine = AiEngine.builder()
                .id(request.id())
                .name(request.name())
                .baseUrl(request.baseUrl())
                .model(request.model())
                .modelsCsv(request.modelsCsv())
                .enabled(request.enabled() == null || request.enabled())
                .priority(request.priority() == null ? 100 : request.priority())
                .remark(request.remark())
                .protocol("openai_chat")
                .build();
        AiEngine saved = aiEngineRegistry.save(engine);
        return ApiResponse.data("已保存引擎 " + saved.getName(), aiEngineRegistry.describeAll());
    }

    /**
     * 只切换启用状态：填了 Key 也可以先不启用，也允许全部停用。
     *
     * @param request 引擎标识与启用状态
     * @return 保存后的全部引擎状态
     */
    @Operation(summary = "启用或停用引擎")
    @PostMapping("/engines/enabled")
    public ApiResponse<Map<String, EngineStatus>> setEngineEnabled(@Valid @RequestBody AiEngineEnabledRequest request) {
        AiEngine saved = aiEngineRegistry.setEnabled(request.id(), request.enabled());
        return ApiResponse.data((saved.isEnabled() ? "已启用 " : "已停用 ") + saved.getName(),
                aiEngineRegistry.describeAll());
    }

    /**
     * 删除一个自定义引擎；内置引擎只能停用。
     *
     * @param request 引擎标识
     * @return 删除后的全部引擎状态
     */
    @PostMapping("/engines/delete")
    public ApiResponse<Map<String, EngineStatus>> deleteEngine(@Valid @RequestBody AiEngineTestRequest request) {
        aiEngineRegistry.delete(request.id());
        return ApiResponse.data("已删除引擎 " + request.id(), aiEngineRegistry.describeAll());
    }

    /**
     * 测试一个引擎：验证端点与鉴权，并发一条样例确认模型会按契约输出可解析的 JSON。
     *
     * @param request 引擎标识
     * @return 是否成功、耗时与原因
     */
    @Operation(summary = "测试引擎连通性")
    @PostMapping("/engines/test")
    public ApiResponse<AiDetectClient.ProbeResult> testEngine(@Valid @RequestBody AiEngineTestRequest request) {
        AiDetectClient.ProbeResult result = aiDetectClient.probe(request.id());
        String message = (result.ok() ? "连接成功（" : "测试失败（") + result.responseMs() + " ms）："
                + result.message();
        return ApiResponse.data(message, result);
    }

    /**
     * 从引擎端点拉取型号清单（GET /models），只扩充候选，不改已勾选的型号池。
     *
     * @param request 引擎标识
     * @return 拉取结果与保存后的全部引擎状态
     */
    @Operation(summary = "从端点拉取型号清单")
    @PostMapping("/engines/discover")
    public ApiResponse<Map<String, EngineStatus>> discoverModels(@Valid @RequestBody AiEngineTestRequest request) {
        List<String> models = aiDetectClient.discoverModels(request.id());
        int added = aiEngineRegistry.mergeModels(request.id(), models);
        String message = added > 0
                ? "拉取到 " + models.size() + " 个型号，新增 " + added + " 个"
                : "拉取到 " + models.size() + " 个型号，清单里都已存在";
        return ApiResponse.data(message, aiEngineRegistry.describeAll());
    }
}
