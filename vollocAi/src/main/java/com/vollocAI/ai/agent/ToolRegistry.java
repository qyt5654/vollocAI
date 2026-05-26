package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * 工具注册表 —— 显式注册，不依赖 Spring 自动收集。
 * 只有通过 register() 手动加入的 Function 才会被当作 Tool 暴露给 AI。
 */
@Component
public class ToolRegistry {

    private record ToolDef(String description, Function<String, String> fn) {}

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    /** 由 ToolConfig 在初始化时调用，显式注册工具 */
    public void register(String name, String description, Function<String, String> fn) {
        tools.put(name, new ToolDef(description, fn));
    }

    public Set<String> getAllToolNames() {
        return tools.keySet();
    }

    public Map<String, String> getToolDescriptions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, ToolDef> e : tools.entrySet()) {
            m.put(e.getKey(), e.getValue().description());
        }
        return m;
    }

    public String callTool(String name, String input) {
        ToolDef def = tools.get(name);
        if (def == null) throw new IllegalArgumentException("unknown tool: " + name);
        return def.fn().apply(input);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<FunctionCallback> buildCallbacks(Set<String> names) {
        List<FunctionCallback> callbacks = new ArrayList<>();
        for (String name : names) {
            ToolDef def = tools.get(name);
            if (def != null) {
                callbacks.add(new FunctionCallback() {
                    @Override public String getName() { return name; }
                    @Override public String getDescription() { return def.description(); }
                    @Override public String getInputTypeSchema() {
                        return "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}";
                    }
                    @Override public String call(String input) { return def.fn().apply(normalizeInput(input)); }
                });
            }
        }
        return callbacks;
    }

    private static String normalizeInput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return raw;
        try {
            JSONObject o = JSON.parseObject(s);
            String v = firstString(o, "input", "args", "expression", "query", "content", "text");
            if (v != null) return v;
            if (o.size() == 1) {
                Object only = o.values().iterator().next();
                return only == null ? "" : String.valueOf(only);
            }
        } catch (Exception ignored) {
        }
        return raw;
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.get(k);
            if (v != null) return String.valueOf(v);
        }
        return null;
    }
}
