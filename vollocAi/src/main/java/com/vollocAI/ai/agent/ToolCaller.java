package com.vollocAI.ai.agent;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
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
     * 任何异常（含工具不存在/模式不匹配/超时/业务异常）统一转为 {@code [result, error, elapsedMs]} 数组返回。
     * <p>
     * ChatModel 通过参数显式传入（无状态设计），在 ForkJoinPool 线程中通过 ThreadLocal 桥接，
     * 供工具链（如 deep_research → AgentToolPlannerService）使用。
     *
     * @param model     当前会话的动态 ChatModel（可空，仅 DEEP 模式需要）
     * @param name      工具名
     * @param input     工具参数
     * @param c         取消标志
     * @param timeoutMs 超时毫秒数
     * @param mode      当前运行模式（用于 {@link ToolRegistry} 的可见性校验）
     */
    String[] call(ChatModel model, String name, String input, AtomicBoolean c, long timeoutMs, ToolMode mode) {
        if (c.get()) return new String[]{"","cancelled","0"};
        long t0 = System.nanoTime();
        try {
            String o = CompletableFuture.supplyAsync(() -> {
                // 【跨线程桥接】supplyAsync 在 ForkJoinPool 线程执行，ThreadLocal 不跨线程
                if (model != null) AgentToolPlannerService.setModel(model);
                try {
                    return tools.callTool(name, input, mode);
                } finally {
                    if (model != null) AgentToolPlannerService.clearModel();
                }
            }).orTimeout(timeoutMs, TimeUnit.MILLISECONDS).get();
            return new String[]{o, null, Long.toString((System.nanoTime() - t0) / 1_000_000L)};
        } catch (Exception e) {
            Throwable x = e.getCause() != null ? e.getCause() : e;
            return new String[]{"", x.getMessage(), Long.toString((System.nanoTime() - t0) / 1_000_000L)};
        }
    }
}
