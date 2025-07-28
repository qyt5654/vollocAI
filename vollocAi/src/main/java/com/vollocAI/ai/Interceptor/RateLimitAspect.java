package com.vollocAI.ai.Interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.vollocAI.ai.annotation.RateLimit;
import com.vollocAI.ai.exception.GatewayExceptionHandler;
import com.vollocAI.ai.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.binding.MapperMethod;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.aspectj.lang.annotation.Aspect;

import java.lang.reflect.Method;

/**
 * 分布式限流拦截器
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        // 获取方法签名
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        // 生成限流 Key（支持用户 ID）
        String key = "rate_limit:" + method.getDeclaringClass().getSimpleName() + ":" + method.getName();
        if (rateLimit.perUser()) {
            key += ":user:" + StpUtil.getLoginIdAsString();
        }

        // 加上当前窗口时间标识（粗粒度控制）
        long window = System.currentTimeMillis() / rateLimit.unit().toMillis(rateLimit.duration());
        key += ":win:" + window;

        // 获取计数器
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        if (!counter.isExists()) {
            counter.expire(rateLimit.duration(), rateLimit.unit());
        }

        // 自增并判断是否超限
        long count = counter.incrementAndGet();
        if (count > rateLimit.limit()) {
            throw new GlobalExceptionHandler();
        }

        // 正常执行原方法
        return pjp.proceed();
    }
}
