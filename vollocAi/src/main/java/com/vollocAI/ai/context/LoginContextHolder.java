package com.vollocAI.ai.context;

import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.context.model.SaTokenContextModelBox;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录上下文对象
 */
public class LoginContextHolder{

    private static final InheritableThreadLocal<Map<String, Object>> THREAD_LOCAL
            = new InheritableThreadLocal<>();

    public static final String LOGIN_ID_KEY = "loginId";


    public static Context withLoginId(Long loginId) {
        return Context.of(LOGIN_ID_KEY, loginId);
    }

    public static void set(String key, Object val){
        Map<String, Object> map = getThreadLocalMap();
        map.put(key, val);
    }

    public static Long getLoginId(){
        return (Long) getThreadLocalMap().get("loginId");
    }

    public static void remove(){
        THREAD_LOCAL.remove();
    }

    public static Object get(String key){
        Map<String, Object> threadLocalMap = getThreadLocalMap();
        return threadLocalMap.get(key);
    }

    public static Map<String, Object> getThreadLocalMap(){
        Map<String, Object> map = THREAD_LOCAL.get();
        if(Objects.isNull(map)){
            map = new ConcurrentHashMap<>();
            THREAD_LOCAL.set(map);
        }
        return map;
    }
}
