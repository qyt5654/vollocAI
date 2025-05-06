package com.vollocAI.ai.Interceptor;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


/**
 * 登录拦截器
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;

    // 注册拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 Sa-Token 拦截器，校验规则为 StpUtil.checkLogin() 登录校验。
//        registry.addInterceptor()
//                .addPathPatterns("/**")
//                .excludePathPatterns("/user/doLogin")
//                .excludePathPatterns("/user/doRegister");

        // 你自定义的拦截器：记录 loginId 到 ThreadLocal 中
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/user/doLogin", "/user/doRegister")
                .order(1);
    }

    @Bean
    public SaServletFilter getSaServletFilter() {
        return new SaServletFilter()
                .addExclude("/ai/ask","/user/doLogin", "/user/doRegister")  // 排除已由 SaReactorFilter 处理的接口
                .addInclude("/**")
                .setAuth(obj -> StpUtil.checkLogin());
    }

}

