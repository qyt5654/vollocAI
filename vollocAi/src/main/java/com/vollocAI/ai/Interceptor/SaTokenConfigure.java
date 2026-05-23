package com.vollocAI.ai.Interceptor;

import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/", "/index.html", "/favicon.ico",
                        "/static/**", "/css/**", "/js/**", "/img/**",
                        "/user/doLogin", "/user/doRegister")
                .order(1);
    }

    @Bean
    public SaServletFilter saServletFilter() {
        return new SaServletFilter()
                .addExclude("/", "/index.html", "/favicon.ico",
                        "/static/**", "/css/**", "/js/**", "/img/**",
                        "/user/doLogin", "/user/doRegister",
                        "/ai/stream/**")
                .addInclude("/**")
                .setAuth(obj -> StpUtil.checkLogin());
    }
}

