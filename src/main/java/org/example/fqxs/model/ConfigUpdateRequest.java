package org.example.fqxs.model;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * POST /api/rank/config 的请求体：两字段都可选，null 表示不改。
 * rankType 的取值域用 Bean Validation 钉住；displayCategories 的 code 归属与去重
 * 交给 ConfigService（需要查分类目录，注解表达不了）。
 */
@Data
public class ConfigUpdateRequest {

    /** read=阅读榜 / new=新书榜。 */
    @Pattern(regexp = "read|new", message = "只支持 read 或 new")
    private String rankType;

    /** 展示分类 code 列表；空数组=清空展示分类。 */
    private List<String> displayCategories;
}
