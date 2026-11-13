package com.example.rtbf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 操作台三类角色：
 *  COMPLIANCE 合规专员：主体确认、断链人工确认、对账复核、模型卡决策、证据包
 *  OPERATOR   运维操作员：枚举冻结、预览、执行、回执登记、增补工单执行
 *  VIEWER     只读审计
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.withUsername("compliance").password(encoder.encode("Compliance@2026"))
                .roles("COMPLIANCE", "VIEWER").build(),
            User.withUsername("operator").password(encoder.encode("Operator@2026"))
                .roles("OPERATOR", "VIEWER").build(),
            User.withUsername("viewer").password(encoder.encode("Viewer@2026"))
                .roles("VIEWER").build()
        );
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/actuator/health").permitAll()
                // 候选人查询门户：免登录，凭限时凭证访问（凭证自身承担访问控制与时效）
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .httpBasic(basic -> {})
            // 不启用表单登录：未认证的 API 调用直接返回 401，而不是 302 跳登录页
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                (request, response, authException) -> response.sendError(401, "Unauthorized")));
        return http.build();
    }
}
