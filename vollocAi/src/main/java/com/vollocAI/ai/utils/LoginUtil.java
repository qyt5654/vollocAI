package com.vollocAI.ai.utils;

import com.vollocAI.ai.context.LoginContextHolder;
/**
 * 用户登录util
 */
public class LoginUtil {

    public static Long getLoginId(){
        return LoginContextHolder.getLoginId();
    }

}
