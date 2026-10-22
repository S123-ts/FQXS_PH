package org.example.fqxs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.fqxs.entity.AiPrompt;
import org.example.fqxs.service.AiPromptRegistry;
import org.example.fqxs.web.ApiResponse;
import org.example.fqxs.web.dto.PromptCatalog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词变体查询：只回元数据与权重，不回正文全文。
 */
@Tag(name = "AI 提示词")
@RestController
@RequestMapping(value = "/api/ai", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
@RequiredArgsConstructor
public class AiPromptController {

    private final AiPromptRegistry promptRegistry;

    /**
     * 提示词变体清单与当前策略。
     *
     * @return {strategy, randomAvailable, prompts: [{id, name, enabled, poolable, weight, origin}]}
     */
    @Operation(summary = "提示词变体清单与当前选取策略")
    @GetMapping("/prompts")
    public ApiResponse<PromptCatalog> prompts() {
        List<PromptCatalog.PromptItem> items = new ArrayList<>();
        for (AiPrompt prompt : promptRegistry.describeAll()) {
            items.add(new PromptCatalog.PromptItem(
                    prompt.getId(),
                    prompt.getName(),
                    prompt.isEnabled(),
                    prompt.isPoolable(),
                    prompt.getWeight(),
                    prompt.getOrigin() == null ? "" : prompt.getOrigin(),
                    prompt.isBuiltin(),
                    prompt.getRemark() == null ? "" : prompt.getRemark()));
        }
        PromptCatalog catalog = new PromptCatalog(
                promptRegistry.isRandomStrategy() ? "random" : "fixed",
                promptRegistry.randomAvailable(),
                items);
        return ApiResponse.data("success", catalog);
    }
}
