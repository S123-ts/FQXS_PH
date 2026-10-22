package org.example.fqxs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息。字段结构由 Controller 的返回类型决定，这里只补标题/版本/说明。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI fqxsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("FQXS 榜单与 AI 判定 API")
                .version("1.0.0")
                .description("本地单机服务；写接口仍需 X-Rank-Token 或管理令牌"));
    }
}
