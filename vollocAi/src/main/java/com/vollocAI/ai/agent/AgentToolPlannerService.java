package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** LLM 选工具 + 代码执行，供 Supervisor 等场景使用 */
@Service
public class AgentToolPlannerService {

    private static final Logger log = LoggerFactory.getLogger(AgentToolPlannerService.class);

    /** ThreadLocal：AgentExecutor 在执行前注入动态模型，工具调用时取出使用 */
    private static final ThreadLocal<ChatModel> currentModel = new ThreadLocal<>();

    @Resource private ToolRegistry toolRegistry;

    public static void setModel(ChatModel model) { currentModel.set(model); }
    public static void clearModel() { currentModel.remove(); }
    public static ChatModel getModel() { return currentModel.get(); }

    public String planAndExecute(String userQuery, String taskContext) {
        ChatModel model = currentModel.get();
        if (model == null) throw new IllegalStateException("未设置动态模型，请通过 AgentExecutor.run() 调用");
        return planAndExecute(userQuery, taskContext, model);
    }

    public String planAndExecute(String userQuery, String taskContext, ChatModel model) {
        // DEEP 模式子规划器：只暴露 DEEP 模式下可见的工具
        String toolList = toolRegistry.getToolDescriptions(ToolMode.DEEP).entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n"));

        String raw = model.call(new Prompt(List.of(
                new SystemMessage("""
                    根据用户问题与任务上下文，决定需要调用哪些工具收集证据（0个或多个）。
                    只输出 JSON，不要 markdown：
                    {"tools":[{"name":"工具名","input":"传给工具的字符串"}]}

                    可用工具：
                    """ + toolList + "\n\n按语义判断需要什么数据；不需要任何工具时返回 {\"tools\":[]}。"),
                new UserMessage("用户问题: " + userQuery + "\n任务上下文: " + taskContext)
        ))).getResult().getOutput().getContent();

        JSONArray tools = parseTools(raw);
        if (tools == null || tools.isEmpty()) return "（本步未调用工具）";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tools.size(); i++) {
            JSONObject t = tools.getJSONObject(i);
            if (t == null) continue;
            String name = t.getString("name"), input = t.getString("input");
            if (name == null || name.isBlank() || !toolRegistry.getAllToolNames(ToolMode.DEEP).contains(name)) continue;
            String arg = input != null && !input.isBlank() ? input : userQuery;
            long t0 = System.nanoTime();
            try {
                String out = toolRegistry.callTool(name, arg, ToolMode.DEEP);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                sb.append("### ").append(name).append(" (").append(ms).append("ms)\n")
                        .append(Objects.toString(out, "")).append("\n\n");
            } catch (Exception e) {
                sb.append("### ").append(name).append(" 失败: ").append(e.getMessage()).append("\n\n");
            }
        }
        return sb.isEmpty() ? "（本步未调用工具）" : sb.toString().trim();
    }

    private static JSONArray parseTools(String raw) {
        String json = ReactProtocol.extractJson(raw);
        if (json == null) return null;
        try { return JSON.parseObject(json).getJSONArray("tools"); }
        catch (Exception e) { return null; }
    }
}
