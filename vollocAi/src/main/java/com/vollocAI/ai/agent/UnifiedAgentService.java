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
import java.util.*;
import java.util.stream.Collectors;

/** 统一 Agent */
@Service
public class UnifiedAgentService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentService.class);
    public static final String A = ReactProtocol.ACT, O = ReactProtocol.OBS, S = ReactProtocol.STATE, P = ReactProtocol.PLAN;

    @Resource private ToolRegistry tools;
    @Resource private ToolCaller caller;
    @Value("${volloc.agent.max-calls:6}") private int maxCalls;
    @Value("${volloc.agent.max-steps:10}") private int maxSteps;
    @Value("${volloc.agent.tool-timeout:30000}") private long toolTimeout;

    public static boolean isProtocolEvent(String t) { return ReactProtocol.isEvent(t); }

    public Flux<String> execute(boolean deep, String query, String apiKey, String apiUrl, String model,
                                 List<Map<String, String>> history) {
        return Flux.create(s -> {
            if (deep) runDeep(query, apiKey, apiUrl, model, history, s);
            else runSimple(query, apiKey, apiUrl, model, history, s);
        });
    }

    private void runSimple(String query, String apiKey, String apiUrl, String model,
                            List<Map<String, String>> history, FluxSink<String> s) {
        var ctx = new AgentContext(simplePlanMessages(history, query));
        var exec = new AgentExecutor(tools, caller, maxCalls, maxSteps, toolTimeout);
        exec.run(AiUtils.model(apiKey, apiUrl, model), ctx, s, exec.SIMPLE, null, null, null);
    }

    private void runDeep(String query, String apiKey, String apiUrl, String model,
                          List<Map<String, String>> past, FluxSink<String> s) {
        var ctx = new AgentContext(List.of());
        ctx.cancelled.set(false);
        s.onCancel(ctx::cancel);
        try {
            ChatModel md = AiUtils.model(apiKey, apiUrl, model, 120);
            AgentExecutor.push(s, "# 任务分析报告\n\n**请求**: "+query+"\n\n", ctx);
            var msgs = new ArrayList<Message>(); msgs.add(new SystemMessage(SUPER_PROMPT)); msgs.addAll(AiUtils.toMessages(past)); msgs.add(new UserMessage(query));
            JSONObject plan = AgentExecutor.parseJson(md.call(new Prompt(msgs)).getResult().getOutput().getContent());
            if (plan == null) plan = fallback(query);
            String tt = plan.getString("task_type"); if (tt == null) tt = "综合分析";
            String rv = plan.getString("rationale"); if (rv == null) rv = query;
            AgentExecutor.push(s, "## 任务类型\n"+tt+"\n\n## 计划\n"+rv+"\n\n", ctx);
            List<String> steps = steps(plan);
            List<String> findings = new ArrayList<>();
            var exec = new AgentExecutor(tools, caller, maxCalls, maxSteps, toolTimeout);
            exec.run(md, ctx, s, exec.DEEP, query, findings, steps);
        } catch (Exception e) { log.error("[DEEP] failed", e); if (!s.isCancelled()) s.error(e); }
    }

    private List<Message> simplePlanMessages(List<Map<String, String>> hist, String query) {
        String tl = tools.getToolDescriptions().entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n"));
        var p = new ArrayList<Message>();
        p.add(new SystemMessage("工具调度器，只输出JSON。\n可用工具：\n"+tl+"\n能直接回答→{\"type\":\"final\"}；需工具→{\"type\":\"tool\",\"name\":\"x\",\"input\":\"y\"}"));
        p.addAll(AiUtils.toMessages(hist)); p.add(new UserMessage(query));
        p.add(new SystemMessage(ANSWER));
        return p;
    }

    private static JSONObject fallback(String q) { JSONObject o=new JSONObject(); o.put("task_type","综合分析"); o.put("rationale",q); o.put("steps",new JSONArray(List.of("收集证据","分析根因给建议"))); return o; }
    private static List<String> steps(JSONObject p) { var a=p.getJSONArray("steps"); if(a==null||a.isEmpty())return List.of(Objects.toString(p.getString("rationale"),"分析请求")); List<String> s=new ArrayList<>(); for(Object o:a)s.add(o.toString()); return s; }

    static final String ANSWER = "工程向助手。紧扣问题，要点回答；不确定说明不确定；不客套。";
    static final String SUPER_PROMPT = "你是Supervisor，分析请求制定计划。严格返回JSON：{\"task_type\":\"告警分析|日志排查|根因定位|日常问答\",\"rationale\":\"一句话\",\"steps\":[\"步骤1\",\"步骤2\"]}";
    static final String EXEC_PROMPT = "你是Executor，根据证据完成调查步骤。引用证据中具体数据；无证据说明缺口。300字内。";
    static final String REPLAN_PROMPT = "你是Replanner，评估进展。返回JSON：{\"decision\":\"CONTINUE|ADJUST|FINISH\",\"adjusted_step\":\"仅ADJUST时\",\"reason\":\"一句话\"}";
}
