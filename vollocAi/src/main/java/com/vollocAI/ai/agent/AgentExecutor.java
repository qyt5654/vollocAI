package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 统一 Agent 执行器 */
class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);
    private final ToolRegistry tools;
    private final ToolCaller caller;
    private final long toolTimeout;

public record Config(int maxIter, int maxToolCalls, boolean replanner) {}
    Config SIMPLE = new Config(10, 6, false), DEEP = new Config(5, 6, true);

    AgentExecutor(ToolRegistry tools, ToolCaller caller, int maxCalls, int perStepMax, long toolTimeout) {
        this.tools = tools; this.caller = caller; this.toolTimeout = toolTimeout;
    }

    /** @param steps null=SIMPLE, non-null=DEEP */
public void run(ChatModel m, AgentContext ctx, FluxSink<String> s, Config cfg, String query, List<String> findings, List<String> steps) {
        var sub = new AtomicReference<reactor.core.Disposable>();
        ctx.cancelled.set(false);
        s.onCancel(() -> { ctx.cancel(); var d = sub.get(); if (d != null) d.dispose(); });
        try {
            boolean simple = steps == null;
            if (simple) ReactProtocol.state(s, "planning", 0, null, null);
            int n = simple ? cfg.maxIter : Math.min(cfg.maxIter, steps.size());
            for (int i = 0; i < n && !ctx.isCancelled(); i++) {
                int sn = i + 1;
                if (simple) {
                    String json = planOnce(m, ctx, sn, s);
                    if (ctx.isCancelled()) { s.complete(); return; }
                    if (json == null) break;
                    var o = ReactProtocol.parseStep(json);
                    if ("final".equals(o.getString("type"))) break;
                    if (!"tool".equals(o.getString("type"))) continue;
                    if (ctx.toolCalls >= cfg.maxToolCalls) { ctx.addUser("已达上限,{\"type\":\"final\"}"); continue; }
                    execTool(m, ctx, s, sn, o.getString("name"), o.getString("input"), json);
                } else {
                    String step = steps.get(i);
                    ReactProtocol.state(s, "step", sn, step, null);
                    push(s, "### 步骤"+sn+": "+step+"\n\n", ctx);
                    // DEEP 走统一 execTool，调用 "deep_research" 工具
                    String toolInput = query + "|" + "步骤: " + step;
                    execTool(m, ctx, s, sn, "deep_research", toolInput, "{\"type\":\"tool\",\"name\":\"deep_research\",\"input\":\""+toolInput+"\"}");
                    // 从 ctx.messages 最后一条提取证据
                    String evidence = extractLastUserContent(ctx);
                    String out = m.call(new Prompt(List.of(new SystemMessage(UnifiedAgentService.EXEC_PROMPT),
                            new UserMessage("步骤: "+step+"\n证据:\n"+evidence+"\n已完成: "+String.join(" | ",findings)))))
                            .getResult().getOutput().getContent();
                    if (out == null) out = "（执行结果为空）";
                    findings.add("步骤"+sn+": "+out);
                    ReactProtocol.emit(s, UnifiedAgentService.S, Map.of("step",sn,"state","step_done","text",ReactProtocol.clip(out,200)));
                    push(s, out+"\n\n", ctx);
                    if (cfg.replanner && i < n-1 && !ctx.isCancelled()) {
                        String rp = m.call(new Prompt(List.of(new SystemMessage(UnifiedAgentService.REPLAN_PROMPT),
                                new UserMessage("步骤"+sn+": "+out+"\n剩余: "+steps.subList(i+1,n)+"\n证据: "+String.join(" | ",findings)))))
                                .getResult().getOutput().getContent();
                        JSONObject rl = parseJson(rp);
                        if (rl != null && "FINISH".equals(rl.getString("decision"))) break;
                        if (rl != null && "ADJUST".equals(rl.getString("decision")) && rl.containsKey("adjusted_step"))
                            steps.set(i+1, rl.getString("adjusted_step"));
                    }
                }
            }
            // 结论：SIMPLE 用 ctx.messages，DEEP 用 findings 组装
            Prompt finalPrompt = simple
                    ? new Prompt(ctx.messages)
                    : new Prompt(List.of(new SystemMessage("运维专家。基于调查输出结论(300字):\n"+String.join("\n",findings)), new UserMessage("生成结论")));
            ReactProtocol.state(s, "answering", null, null, null);
            sub.set(stream(m, finalPrompt).takeWhile(t -> !ctx.isCancelled()).subscribe(s::next, s::error, s::complete));
        } catch (Exception e) { log.error("[Executor] failed", e); if (!s.isCancelled()) s.error(e); }
    }

    /** 从 ctx.messages 最后一条 UserMessage 提取内容 */
    private static String extractLastUserContent(AgentContext ctx) {
        for (int i = ctx.messages.size() - 1; i >= 0; i--)
            if (ctx.messages.get(i) instanceof UserMessage) return ctx.messages.get(i).getContent();
        return "";
    }

    private void execTool(ChatModel m, AgentContext ctx, FluxSink<String> s, int step, String name, String input, String json) {
        if (name == null || name.isBlank() || !tools.getAllToolNames().contains(name)) return;
        ctx.toolCalls++;
        ReactProtocol.emit(s, UnifiedAgentService.A, Map.of("step",step,"tool",name,"input",Objects.toString(input,""),"calls",ctx.toolCalls));
        var r = caller.call(name, input, ctx.cancelled, toolTimeout);
        ReactProtocol.emit(s, UnifiedAgentService.O, ReactProtocol.obs(step,name,Long.parseLong(r[2]),r[0],r[1]));
        ctx.addAssistant(json);
        ctx.addUser("结果:\n"+ReactProtocol.clip(r[1]!=null?"ERR:"+r[1]:r[0],2000));
        ReactProtocol.state(s, "planning", step, null, null);
    }

    private String planOnce(ChatModel m, AgentContext ctx, int step, FluxSink<String> s) {
        String raw = collect(m, new Prompt(ctx.messages), s, step, ctx.cancelled);
        String json = ReactProtocol.extractJson(raw);
        if (json != null) return json;
        ctx.addAssistant(raw); ctx.addUser("JSON无效，只输出{\"type\":\"tool/final\"}");
        if (ctx.isCancelled()) return null;
        return ReactProtocol.extractJson(collect(m, new Prompt(ctx.messages), s, step, ctx.cancelled));
    }

    private String collect(ChatModel m, Prompt p, FluxSink<String> s, int step, AtomicBoolean c) {
        var sb = new StringBuilder();
        stream(m, p).takeWhile(t -> !c.get())
                .doOnNext(ch -> { sb.append(ch); ReactProtocol.emit(s, UnifiedAgentService.P, Map.of("step",step,"delta",ch)); })
                .blockLast();
        return sb.toString();
    }

public static void push(FluxSink<String> s, String text, AgentContext ctx) {
        if (ctx.isCancelled()) return;
        for (char ch : text.toCharArray()) { if (ctx.isCancelled()) return; s.next(String.valueOf(ch)); }
    }

public static JSONObject parseJson(String raw) {
        String j = ReactProtocol.extractJson(raw);
        if (j == null) return null;
        try { return JSONObject.parseObject(j); } catch (Exception e) { return null; }
    }

public static Flux<String> stream(ChatModel m, Prompt p) {
        return m.stream(p).map(r -> { String c = r.getResult().getOutput().getContent(); return c != null ? c : ""; }).filter(t -> !t.isEmpty());
    }
}
