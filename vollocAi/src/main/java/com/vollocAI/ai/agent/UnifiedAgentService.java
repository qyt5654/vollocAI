package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import com.vollocAI.ai.llm.AiUtils;
import com.vollocAI.ai.llm.IntentRecognitionService;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 服务 —— 入口层，按意图分发 SIMPLE / DEEP 模式。
 *
 * <h3>模式</h3>
 * SIMPLE: 日常问答，LLM 逐轮 JSON 决策，只暴露基础工具（计算器、时间）
 * DEEP:  复杂调查，Supervisor 制定计划 → 多步执行 → Replanner 反思 → RAG + 工具协作
 */
@Service
public class UnifiedAgentService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentService.class);

    public static final String A = ReactProtocol.ACT, O = ReactProtocol.OBS,
                               S = ReactProtocol.STATE, P = ReactProtocol.PLAN;

    @Resource private ToolRegistry tools;
    @Resource private ToolCaller caller;
    @Resource private AgentToolPlannerService toolPlanner;
    @Value("${volloc.agent.tool-timeout:30000}") private long toolTimeout;

    // ======================== Prompts ========================

    /** SIMPLE 回答风格 */
    static final String ANSWER = "你是智能助手，用自然语言直接回答用户问题。回答要准确、全面、有深度，给出具体可操作的细节。不确定就说不知道。";

    /** DEEP 规划者：分析问题，制定分步解决计划 */
    static final String SUPER_PROMPT = "你是智能助手，分析用户问题并制定分步解决计划。"
            + "严格返回JSON：{\"rationale\":\"一句话分析\",\"steps\":[\"步骤1\",\"步骤2\"]}";

    /** DEEP 执行者：基于检索结果完成当前步骤，严禁编造 */
    static final String EXEC_PROMPT = "基于检索到的资料完成当前步骤。"
            + "只引用资料中已存在的内容, 严禁编造。无相关资料直接说: 本步未找到相关信息。150字内。";

    /** DEEP 反思者：LLM 自判断是否结束，不做二次校验 */
    static final String REPLAN_PROMPT = """
            评估进展。
            
            【重要】如果原始问题是要求"制定计划/学习路线/分阶段方案"，必须执行完全部步骤，
            确保返回结果覆盖所有规划的阶段。不要提前结束。
            
            优先继续执行剩余步骤，只有在已有充足信息完全回答用户问题时才返回FINISH。
            
            其他情况：只有在已有充足信息完全回答用户问题时才返回 FINISH。
            通常情况返回 CONTINUE。
            
            返回JSON：{"decision":"CONTINUE|FINISH","reason":"..."}
            """;

    // ======================== 入口 ========================

    public static boolean isProtocolEvent(String t) {
        return ReactProtocol.isEvent(t);
    }

    public Flux<String> execute(boolean deep, String query, String apiKey, String apiUrl,
                                String model, List<Map<String, String>> history) {

        ChatModel md = AiUtils.model(apiKey, apiUrl, model, 120);

        ArrayList<Message> msgs = new ArrayList<>();
        msgs.addAll(AiUtils.toMessages(history));
        msgs.add(new UserMessage(query));

        return Flux.create(s -> {
            if (deep) {
                // ======================== DEEP ========================
                AgentContext ctx = new AgentContext(AiUtils.toMessages(history));
                ctx.cancelled.set(false);
                try {
                    // Supervisor 制定调查计划
                    msgs.add(new SystemMessage(SUPER_PROMPT));
                    String planRaw = AiUtils.call(md, new Prompt(msgs));
                    JSONObject plan = planRaw.isEmpty() ? null : AiUtils.parseJson(planRaw);
                    if (plan == null) plan = fallback(query);

                    List<String> steps = steps(plan);
                    AgentExecutor exec = new AgentExecutor(tools, caller, toolPlanner, toolTimeout);
                    exec.run(md, ctx, s, exec.DEEP, query, new ArrayList<>(), steps);
                } catch (Exception e) {
                    log.error("[DEEP] failed", e);
                    if (!s.isCancelled()) s.error(e);
                }
            }
            else {
                // ======================== SIMPLE ========================
                String tl = tools.getToolDescriptions(ToolMode.SIMPLE)
                        .entrySet().stream()
                        .map(e -> "- " + e.getKey() + ": " + e.getValue())
                        .collect(Collectors.joining("\n"));

                msgs.add(new SystemMessage(
                        "工具调度器，只输出JSON。\n可用工具：\n" + tl
                                + "\n能直接回答→{\"type\":\"final\"}；需工具→{\"type\":\"tool\",\"name\":\"x\",\"input\":\"y\"}"));
                msgs.add(new SystemMessage(ANSWER));
                AgentContext ctx = new AgentContext(msgs);
                AgentExecutor exec = new AgentExecutor(tools, caller, toolPlanner, toolTimeout);

                exec.run(md, ctx, s, exec.SIMPLE, null, null, null);
            }
        });
    }


    private static JSONObject fallback(String q) {
        JSONObject o = new JSONObject();
        o.put("task_type", "综合分析");
        o.put("rationale", q);
        o.put("steps", new JSONArray(List.of("收集证据", "分析根因给建议")));
        return o;
    }

    private static List<String> steps(JSONObject p) {
        JSONArray a = p.getJSONArray("steps");
        if (a == null || a.isEmpty())
            return List.of(Objects.toString(p.getString("rationale"), "分析请求"));
        List<String> s = new ArrayList<>();
        for (Object o : a) s.add(o.toString());
        return s;
    }
}
