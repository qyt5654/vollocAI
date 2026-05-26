package com.vollocAI.ai.infra;

import cn.dev33.satoken.stp.StpUtil;
import com.vollocAI.ai.infra.LoginContextHolder;
import jakarta.annotation.Nullable;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return false;
        }
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;
        if (StpUtil.isLogin()) {
            Long loginId = StpUtil.getLoginIdAsLong();
            LoginContextHolder.set("loginId", loginId);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        LoginContextHolder.remove();
    }
}
