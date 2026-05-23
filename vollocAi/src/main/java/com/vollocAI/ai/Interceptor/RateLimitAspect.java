package com.vollocAI.ai.Interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.vollocAI.ai.annotation.RateLimit;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 分布式限流切面 —— 基于 Redisson + 自定义 @RateLimit 注解。
 *
 * 工作原理：
 *   1. 每个方法 + 用户 + 时间窗口生成唯一 Redis key
 *   2. 该 key 对应的计数器自增（原子操作）
 *   3. 超过 limit 则抛异常拒绝请求
 *   4. 窗口过期后 key 自动删除，计数器归零
 *
 * 为什么用 Redisson 而不是本地计数器？
 *   本地计数器（如 Guava RateLimiter）只在单个 JVM 内有效。
 *   多实例部署时，Redisson 基于 Redis 的原子计数器才能真正做到全局限流。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedissonClient redissonClient;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        // 构造限流 key: rate_limit:类名:方法名[:user:用户ID]:win:窗口序号
        String key = "rate_limit:" + method.getDeclaringClass().getSimpleName()
                + ":" + method.getName();
        if (rateLimit.perUser())
            key += ":user:" + StpUtil.getLoginIdAsString();

        long window = System.currentTimeMillis() / rateLimit.unit().toMillis(rateLimit.duration());
        key += ":win:" + window;

        // 获取 Redis 中的原子计数器
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        if (!counter.isExists())
            counter.expire(rateLimit.duration(), rateLimit.unit());

        long count = counter.incrementAndGet();
        if (count > rateLimit.limit())
            throw new RuntimeException("请求过于频繁，请稍后再试");

        // 未超限，正常执行
        return pjp.proceed();
    }
}
