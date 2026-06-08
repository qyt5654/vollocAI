package com.vollocAI.ai.agent;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * 工具注册表 —— 显式注册，不依赖 Spring 自动收集。
 * <p>
 * 只有通过 {@link #register} 手动加入的 Function 才会被当作 Tool 暴露给 AI。
 * 每个工具在注册时声明自己适用的 {@link ToolMode}，调度层与执行层按 mode 过滤/校验，
 * 避免在调用方硬编码黑名单（参见 {@code UnifiedAgentService} 旧实现）。
 */
@Component
public class ToolRegistry {

    private record ToolDef(String description, Function<String, String> fn, ToolMode mode) {}

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    /**
     * 显式注册工具，并声明其适用模式。
     *
     * @param name        工具名（LLM 在 JSON 里使用）
     * @param description 工具描述（展示给 LLM）
     * @param mode        工具适用的运行模式
     * @param fn          工具实现
     */
    public void register(String name, String description, ToolMode mode, Function<String, String> fn) {
        tools.put(name, new ToolDef(description, fn, mode != null ? mode : ToolMode.ALL));
    }

    /** 便捷重载：默认 {@link ToolMode#ALL}（适用于 MCP 自动发现等不关心模式的场景） */
    public void register(String name, String description, Function<String, String> fn) {
        register(name, description, ToolMode.ALL, fn);
    }

    /** 返回指定运行模式下可见的工具名集合 */
    public Set<String> getAllToolNames(ToolMode mode) {
        Set<String> r = new LinkedHashSet<>();
        for (Map.Entry<String, ToolDef> e : tools.entrySet()) {
            if (e.getValue().mode().visibleIn(mode)) r.add(e.getKey());
        }
        return r;
    }

    /** 返回指定运行模式下可见的工具描述（用于构建 LLM prompt） */
    public Map<String, String> getToolDescriptions(ToolMode mode) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, ToolDef> e : tools.entrySet()) {
            if (e.getValue().mode().visibleIn(mode)) {
                m.put(e.getKey(), e.getValue().description());
            }
        }
        return m;
    }

    /**
     * 按运行模式调用工具 —— 业务代码统一走这个方法。
     * <p>
     * 同时校验工具存在性和 mode 可见性：工具不存在或在当前模式下不可见，均抛 {@link IllegalArgumentException}。
     * 调用方（{@link ToolCaller}）会捕获异常并转为错误字符串返回给 LLM。
     *
     * @param name   工具名
     * @param input  工具参数
     * @param mode   当前运行模式
     * @throws IllegalArgumentException 工具不存在或在当前模式下不可见
     */
    public String callTool(String name, String input, ToolMode mode) {
        ToolDef def = tools.get(name);
        if (def == null) throw new IllegalArgumentException("unknown tool: " + name);
        if (!def.mode().visibleIn(mode))
            throw new IllegalArgumentException("tool not available in " + mode + ": " + name);
        return def.fn().apply(input);
    }
}