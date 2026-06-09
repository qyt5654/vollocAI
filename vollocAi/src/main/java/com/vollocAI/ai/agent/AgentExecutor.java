package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.FluxSink;

import com.vollocAI.ai.llm.AiUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    private final ToolRegistry tools;
    private final ToolCaller caller;
    private final AgentToolPlannerService toolPlanner;
    private final long toolTimeout;

    public record Config(int maxIter, ToolMode mode) {}

    Config SIMPLE = new Config(10, ToolMode.SIMPLE);
    Config DEEP   = new Config(15, ToolMode.DEEP);

    AgentExecutor(ToolRegistry tools, ToolCaller caller, AgentToolPlannerService toolPlanner, long toolTimeout) {
        this.tools = tools;
        this.caller = caller;
        this.toolPlanner = toolPlanner;
        this.toolTimeout = toolTimeout;
    }

    // ==================== 入口 ====================

    public void run(ChatModel m, AgentContext ctx, FluxSink<String> s, Config cfg,
                    String query, List<String> findings, List<String> steps) {
        AtomicReference<reactor.core.Disposable> sub = new AtomicReference<>();
        ctx.cancelled.set(false);
        s.onCancel(() -> {
            reactor.core.Disposable d = sub.get();
            if (d != null) d.dispose();
        });

        try {
            // 【无状态设计】ChatModel 通过参数显式传递，无 ThreadLocal / 全局状态
            boolean simple = steps == null;
            if (simple) ReactProtocol.state(s, "planning", 0, null, null);
            int n = simple ? cfg.maxIter : Math.min(cfg.maxIter, steps.size());

            for (int i = 0; i < n && !ctx.isCancelled(); i++) {
                int sn = i + 1;

                if (simple) {
                    // ==================== SIMPLE ====================
                    String json = planOnce(m, ctx, sn);
                    if (ctx.isCancelled()) { s.complete(); return; }
                    if (json == null) break;

                    JSONObject o = JSON.parseObject(json);

                    if ("final".equals(o.getString("type"))) {
                        String output = o.getString("output");
                        if (output != null && !output.isBlank()) {
                            ReactProtocol.state(s, "answering", null, null, null);
                            push(s, output, ctx);
                            s.complete();
                            return;
                        }
                        List<Message> finalMsgs = new ArrayList<>();
                        finalMsgs.add(new SystemMessage("你是智能助手, 用自然语言直接回答。回答要全面、有深度，给出具体可操作的细节。"));
                        for (Message msg : ctx.messages) {
                            if (msg instanceof SystemMessage) continue;
                            finalMsgs.add(msg);
                        }
                        ReactProtocol.state(s, "answering", null, null, null);
                        sub.set(AiUtils.stream(m, new Prompt(finalMsgs))
                                .takeWhile(t -> !ctx.isCancelled())
                                .subscribe(s::next, s::error, s::complete));
                        return;
                    }

                    if (!"tool".equals(o.getString("type"))) continue;
                    execTool(cfg, ctx, s, sn, o.getString("name"), o.getString("input"), json);

                } else {
                    // ==================== DEEP ====================

                    String step = steps.get(i);
                    ReactProtocol.state(s, "step", sn, step, null);

                    // 【直接调用】deep_research 不走 ToolCaller/ToolRegistry，
                    // ChatModel m 直接传给 planAndExecute，完全无状态
                    String toolInput = query + "|" + step;

                    long t0 = System.nanoTime();
                    String toolResult;
                    try {
                        toolResult = toolPlanner.planAndExecute(query, step, m);
                        ReactProtocol.emit(s, UnifiedAgentService.A, Map.of("step", sn, "tool", "deep_research", "input", toolInput, "calls", ctx.toolCalls));
                    } catch (Exception e) {
                        toolResult = "ERR:" + e.getMessage();
                    }
                    long elapsed = (System.nanoTime() - t0) / 1_000_000L;
                    ReactProtocol.emit(s, UnifiedAgentService.O, ReactProtocol.obs(sn, "deep_research", elapsed, toolResult, toolResult.startsWith("ERR:") ? toolResult.substring(4) : null));

                    String json = JSON.toJSONString(Map.of("type", "tool", "name", "deep_research", "input", toolInput));
                    ctx.addAssistant(json);
                    ctx.addUser("结果:\n" + ReactProtocol.clip(toolResult, 2000));

                    log.info("[deep_research] result(length={}): {}", toolResult.length(), toolResult.length() > 300 ? toolResult.substring(0, 300) + "..." : toolResult);

                    //组装Msg进行单步询问
                    String evidence = extractLastUserContent(ctx);
                    List<Message> execMsgs = new ArrayList<>();
                    execMsgs.add(new SystemMessage(UnifiedAgentService.EXEC_PROMPT));
                    execMsgs.addAll(recentContext(ctx, 6));
                    execMsgs.add(new UserMessage(step + "\n证据:\n" + evidence
                            + "\n已完成: " + String.join(" | ", findings)));
                    String out = AiUtils.call(m, new Prompt(execMsgs));
                    if (out == null) out = "（执行结果为空）";
                    log.info("[Executor-步骤{}] out前100字={}",
                            sn, out.length() > 100 ? out.substring(0, 100) + "..." : out);
                    findings.add(out);
                    ReactProtocol.emit(s, UnifiedAgentService.S, Map.of("step", sn, "state", "step_done", "text", ReactProtocol.clip(out, 200)));

                    // ========== 终止判断 ==========
                    if (i < n - 1 && !ctx.isCancelled()) {
                        if (sn >= cfg.maxIter) {
                            log.info("[Replanner] 达到最大步数 maxIter={}, 强制结束", cfg.maxIter);
                            break;
                        }
                        if (isRepeating(findings)) {
                            log.info("[Replanner] 检测到重复结果，提前结束");
                            break;
                        }

                        String rp = AiUtils.call(m, new Prompt(List.of(
                                new SystemMessage(UnifiedAgentService.REPLAN_PROMPT),
                                new UserMessage(ReactProtocol.clip(out, 1500) + "\n剩余: "
                                        + String.join(" | ", steps.subList(i + 1, n))
                                        + "\n证据: " + ReactProtocol.clip(String.join(" | ", lastN(findings, 3)), 800)))));

                        JSONObject rl = AiUtils.parseJson(rp);
                        if (rl != null && "FINISH".equals(rl.getString("decision"))) {
                            log.info("[Replanner] LLM 判断 FINISH: {}", rl.getString("reason"));
                            break;
                        }
                    }
                }
            }

            // ==================== 最终回答 ====================
            if (simple) {
                if (!ctx.messages.isEmpty() && ctx.messages.get(0) instanceof SystemMessage) {
                    ctx.messages.set(0, new SystemMessage("你是智能助手, 用自然语言直接回答。回答要全面、有深度，给出具体可操作的细节。"));
                }
            }
            Prompt finalPrompt = simple
                    ? new Prompt(ctx.messages)
                    : buildDeepFinalPrompt(findings, ctx);
            ReactProtocol.state(s, "answering", null, null, null);
            AiUtils.logPrompt("【最终回答Prompt】", finalPrompt);
            sub.set(AiUtils.stream(m, finalPrompt)
                    .takeWhile(t -> !ctx.isCancelled())
                    .subscribe(s::next, s::error, s::complete));
        } catch (Throwable t) {
            log.error("[Executor] failed", t);
            if (!s.isCancelled()) s.error(t);
        }
    }

    // ==================== 重复检测防止死循环 ====================
    private boolean isRepeating(List<String> findings) {
        if (findings == null || findings.size() < 2) return false;
        String last = findings.get(findings.size() - 1);
        String prev = findings.get(findings.size() - 2);
        if (last == null || prev == null) return false;
        if (last.equals(prev)) return true;
        if (last.contains("失败") && prev.contains("失败")
                && last.replaceAll("\\d+", "").equals(prev.replaceAll("\\d+", "")))
            return true;
        if (hasRepeatingKeywords(last, prev)) return true;
        return textSimilarity(last, prev) > 0.75;
    }

    private boolean hasRepeatingKeywords(String a, String b) {
        String[] keywords = {"未找到", "超时", "连接失败", "权限不足", "不存在", "无法访问", "错误", "异常"};
        for (String kw : keywords) {
            if (a.contains(kw) && b.contains(kw)) return true;
        }
        return false;
    }

    private double textSimilarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int m = a.length(), n = b.length(), maxLen = 0;
        int[] dp = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            int prevDiag = 0;
            for (int j = 1; j <= n; j++) {
                int temp = dp[j];
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[j] = prevDiag + 1;
                    maxLen = Math.max(maxLen, dp[j]);
                } else {
                    dp[j] = 0;
                }
                prevDiag = temp;
            }
        }
        return maxLen / (double) Math.max(m, n);
    }

    private List<String> lastN(List<String> list, int n) {
        if (list.size() <= n) return new ArrayList<>(list);
        return new ArrayList<>(list.subList(list.size() - n, list.size()));
    }

    // ==================== SIMPLE 规划 ====================

    private String planOnce(ChatModel m, AgentContext ctx, int step) {
        if (ctx.isCancelled()) return null;

        Prompt prompt = new Prompt(ctx.messages);
        AiUtils.logPrompt("【规划Prompt-步骤" + step + "】", prompt);
        String raw = AiUtils.call(m, prompt);
        if (ctx.isCancelled()) return null;

        String json = ReactProtocol.extractJson(raw);
        if (json != null) return json;

        String trimmed = raw.trim();
        if (!trimmed.isEmpty()) {
            log.info("【规划-步骤{}】LLM 未输出JSON，已包装为 final", step);
            return "{\"type\":\"final\",\"output\":" + JSON.toJSONString(trimmed) + "}";
        }

        ctx.addAssistant(raw);
        ctx.addUser("JSON无效，只输出{\"type\":\"tool/final\"}");
        if (ctx.isCancelled()) return null;
        raw = AiUtils.call(m, new Prompt(ctx.messages));
        if (ctx.isCancelled()) return null;
        return ReactProtocol.extractJson(raw);
    }

    // ==================== 工具执行（SIMPLE 模式）====================

    /** SIMPLE 模式工具调用，经 ToolCaller → ToolRegistry */
    private void execTool(Config cfg, AgentContext ctx, FluxSink<String> s,
                          int step, String name, String input, String json) {
        if (name == null || name.isBlank() || !tools.getAllToolNames(cfg.mode()).contains(name)) return;
        ctx.toolCalls++;
        ReactProtocol.emit(s, UnifiedAgentService.A,
                Map.of("step", step, "tool", name, "input", Objects.toString(input, ""), "calls", ctx.toolCalls));
        String[] r = caller.call(name, input, ctx.cancelled, toolTimeout, cfg.mode());
        ReactProtocol.emit(s, UnifiedAgentService.O,
                ReactProtocol.obs(step, name, Long.parseLong(r[2]), r[0], r[1]));
        String toolResult = r[1] != null ? "ERR:" + r[1] : r[0];
        ctx.addAssistant(json);
        ctx.addUser("结果:\n" + ReactProtocol.clip(toolResult, 2000));
        log.info("[execTool] tool={} result(length={}): {}", name, toolResult.length(),
                toolResult.length() > 300 ? toolResult.substring(0, 300) + "..." : toolResult);
        ReactProtocol.state(s, "planning", Integer.valueOf(step), null, null);
    }

    // ==================== 上下文 & Prompt 构建 ====================

    private static Prompt buildDeepFinalPrompt(List<String> findings, AgentContext ctx) {
        int originalSize = findings.size();
        List<String> truncated = new ArrayList<>();
        int start = Math.max(0, findings.size() - 3);
        for (int i = start; i < findings.size(); i++) {
            String f = findings.get(i);
            truncated.add(f.length() > 200 ? f.substring(0, 200) + "..." : f);
        }
        int totalChars = truncated.stream().mapToInt(String::length).sum();
        log.info("[FinalPrompt] findings 原始{}条 → 截断{}条, 总字数{}",
                originalSize, truncated.size(), totalChars);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("你是智能助手, 基于分析结果回答用户问题。\n"
                + "若分析结果中有有效信息, 基于信息给出具体回答。\n"
                + "严禁编造。回答要全面、有深度。\n分析结果:\n"
                + String.join("\n", truncated)));
        msgs.addAll(recentContext(ctx, 6));
        msgs.add(new UserMessage("请回答"));
        return new Prompt(msgs);
    }

    private static List<Message> recentContext(AgentContext ctx, int limit) {
        List<Message> result = new ArrayList<>();
        int count = 0;
        for (int i = ctx.messages.size() - 1; i >= 0 && count < limit; i--) {
            Message msg = ctx.messages.get(i);
            if (msg instanceof SystemMessage) continue;
            result.add(msg);
            count++;
        }
        Collections.reverse(result);
        return result;
    }

    private static String extractLastUserContent(AgentContext ctx) {
        for (int i = ctx.messages.size() - 1; i >= 0; i--)
            if (ctx.messages.get(i) instanceof UserMessage)
                return ctx.messages.get(i).getContent();
        return "";
    }

    // ==================== 输出辅助 ====================

    public static void push(FluxSink<String> s, String text, AgentContext ctx) {
        if (ctx.isCancelled()) return;
        final int BATCH = 8;
        StringBuilder batch = new StringBuilder(BATCH);
        for (int i = 0; i < text.length(); i++) {
            if (ctx.isCancelled()) return;
            batch.append(text.charAt(i));
            if (batch.length() >= BATCH || i == text.length() - 1) {
                s.next(batch.toString());
                batch.setLength(0);
            }
        }
    }
}
