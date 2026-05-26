package com.vollocAI.ai.infra;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录上下文 —— 基于 InheritableThreadLocal 传递当前请求的 loginId
 */
public class LoginContextHolder {

    private static final ThreadLocal<Map<String, Object>> THREAD_LOCAL
            = new ThreadLocal<>();

    public static void set(String key, Object val) {
        getThreadLocalMap().put(key, val);
    }

    public static Long getLoginId() {
        return (Long) getThreadLocalMap().get("loginId");
    }

    public static void remove() {
        THREAD_LOCAL.remove();
    }

    private static Map<String, Object> getThreadLocalMap() {
        Map<String, Object> map = THREAD_LOCAL.get();
        if (Objects.isNull(map)) {
            map = new ConcurrentHashMap<>();
            THREAD_LOCAL.set(map);
        }
        return map;
    }
}
