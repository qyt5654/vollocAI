package com.vollocAI.ai.agent;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 统一工具调用器 —— 负责超时控制 + 异常转错误字符串，供 AgentExecutor 使用 */
@Component
public class ToolCaller {
    @Resource private ToolRegistry tools;

    /**
     * 单工具调用（带模式权限校验）。
     * <p>
     * 在独立线程中执行工具（受 timeoutMs 限制），内部通过
     * {@link ToolRegistry#callTool(String, String, ToolMode)} 做存在性 + 模式可见性双重校验，
     * 任何异常统一转为 {@code [result, error, elapsedMs]} 数组返回。
     *
     * @param name      工具名
     * @param input     工具参数
     * @param c         取消标志
     * @param timeoutMs 超时毫秒数
     * @param mode      当前运行模式
     */
    String[] call(String name, String input, AtomicBoolean c, long timeoutMs, ToolMode mode) {
        if (c.get()) return new String[]{"","cancelled","0"};
        long t0 = System.nanoTime();
        try {
            String o = CompletableFuture.supplyAsync(() -> tools.callTool(name, input, mode))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS).get();
            return new String[]{o, null, Long.toString((System.nanoTime() - t0) / 1_000_000L)};
        } catch (Exception e) {
            Throwable x = e.getCause() != null ? e.getCause() : e;
            return new String[]{"", x.getMessage(), Long.toString((System.nanoTime() - t0) / 1_000_000L)};
        }
    }
}
