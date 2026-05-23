package com.vollocAI.ai.service;

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

    private final Map<String, Function<String, String>> tools = new LinkedHashMap<>();

    /** 由 ToolConfig 在初始化时调用，显式注册工具 */
    public void register(String name, String description, Function<String, String> fn) {
        tools.put(name, fn);
    }

    public Set<String> getAllToolNames() {
        return tools.keySet();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<FunctionCallback> buildCallbacks(Set<String> names) {
        List<FunctionCallback> callbacks = new ArrayList<>();
        for (String name : names) {
            Function<String, String> fn = tools.get(name);
            if (fn != null) {
                callbacks.add(new FunctionCallback() {
                    @Override public String getName() { return name; }
                    @Override public String getDescription() { return name; }
                    @Override public String getInputTypeSchema() { return "{\"type\":\"string\"}"; }
                    @Override public String call(String input) { return fn.apply(input); }
                });
            }
        }
        return callbacks;
    }
}
