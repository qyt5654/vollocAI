package com.vollocAI.ai.agent;

/**
 * 工具适用模式 —— 声明式权限控制。
 * 每个工具在注册时声明自己适用的模式，调度层和执行层按 mode 过滤/校验，
 * 取代调用方硬编码黑名单的做法（符合开闭原则）。
 */
public enum ToolMode {

    // 所有模式都可用
    ALL,
    // 仅日常问答模式
    SIMPLE,
    // 仅复杂调查模式（含子规划器）
    DEEP;

    // 从描述中解析出的模式 + 清洗后的描述
    public record Parsed(ToolMode mode, String cleanDescription) {}

    //从 MCP Server 返回的 tool description 中解析 ToolMode。
    public static Parsed fromDescription(String description) {
        if (description == null || description.isBlank()) return new Parsed(ALL, "");
        String upper = description.toUpperCase();
        ToolMode mode = ALL;
        if (upper.contains("[DEEP]")) mode = DEEP;
        else if (upper.contains("[SIMPLE]")) mode = SIMPLE;
        String clean = description.replaceAll("(?i)\\[(DEEP|SIMPLE|ALL)]\\s*", "").trim();
        return new Parsed(mode, clean);
    }

    //当前 mode 在指定运行模式下是否可见。
    public boolean visibleIn(ToolMode runtime) {
        if (this == ALL) return true;
        return this == runtime;
    }
}