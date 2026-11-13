package com.example.rtbf.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI rtbfOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("候选人注销（被遗忘权）工单平台 API")
                .version("1.0.0")
                .description("主体归并、血缘枚举冻结、四类处置、回执对账、模型卡与证据包"))
            .components(new Components().addSecuritySchemes("basic",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
            .addSecurityItem(new SecurityRequirement().addList("basic"));
    }
}
