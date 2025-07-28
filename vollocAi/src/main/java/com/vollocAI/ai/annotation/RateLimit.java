package com.vollocAI.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /**
     * 时间窗口内最多允许的请求次数
     */
    long limit();

    /**
     * 时间窗口大小
     */
    long duration();

    /**
     * 时间单位
     */
    TimeUnit unit() default TimeUnit.MINUTES;

    /**
     * 是否根据登录用户进行限流（否则统一限流）
     */
    boolean perUser() default true;
}
