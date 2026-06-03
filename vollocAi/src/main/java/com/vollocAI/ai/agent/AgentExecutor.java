package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSON;
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

/**
 * Agent 执行器 —— SIMPLE / DEEP 双模式的核心引擎。
 *
 * <h2>架构</h2>
 * <pre>
 *   UnifiedAgentService
 *     │
 *     ├─ runSimple() → AgentExecutor.run(cfg=SIMPLE, steps=null)
 *     │                  └─ planOnce() 循环: LLM JSON决策 → final/tool → 回答
 *     │
 *     └─ runDeep()   → AgentExecutor.run(cfg=DEEP,   steps=[...])
 *                        └─ 逐步骤: execTool → Executor LLM → Replanner → findings
 * </pre>
 *
 * <h2>SIMPLE 模式（日常问答）</h2>
 * <ol>
 *   <li><b>规划</b>：LLM 分析问题 → 输出 JSON 决策。<br>
 *       {@code {"type":"final"}} = 可直接回答，{@code {"type":"tool","name":"x","input":"y"}} = 需调工具</li>
 *   <li><b>工具调用</b>：type=tool → 执行工具 → 结果注入上下文(ctx.messages) → 回到步骤1</li>
 *   <li><b>回答</b>：type=final → 有output直接推送 / 无output构建干净prompt再调LLM</li>
 *   <li><b>后备</b>：循环耗尽/json=null → 替换首条SystemMessage → 流式生成最终答案</li>
 * </ol>
 *
 * <h2>DEEP 模式（复杂调查，RAG+工具协作）</h2>
 * <ol>
 *   <li><b>Supervisor</b>：分析问题制定多步计划 → steps[]（在UnifiedAgentService.runDeep中完成）</li>
 *   <li><b>逐步执行</b>：每步调deep_research工具 → Executor LLM分析证据 → findings累积</li>
 *   <li><b>Replanner反思</b>：评估进展 → CONTINUE(继续) / ADJUST(调整) / FINISH(提前结束)</li>
 *   <li><b>最终结论</b>：findings汇总 + 历史上下文 → 流式生成答案</li>
 * </ol>
 * 注意：DEEP步骤中间结果仅入工具链面板([[STATE]]/[[OBS]])，不推正文区。
 *
 * <h2>SSE事件协议（前端工具链面板）</h2>
 * <table>
 *   <tr><td>{@code [[STATE]]}</td><td>状态变更: planning / tool_start / answering / error</td></tr>
 *   <tr><td>{@code [[ACT]]}</td><td>工具调用开始，携带tool名和input</td></tr>
 *   <tr><td>{@code [[OBS]]}</td><td>工具调用结果，携带output和耗时</td></tr>
 *   <tr><td>{@code [[PLAN]]}</td><td>规划阶段LLM token增量（前端入planBuf，不显示）</td></tr>
 * </table>
 * 普通文本token逐字符经Redis→SSE→前端累加为fullText→Markdown渲染。
 *
 * <h2>数据流</h2>
 * <pre>
 *   push(s, text) → FluxSink.next(char) → AiTaskExecutor.doOnNext
 *     → redis.rightPush → SSE控制器BLPOP → emitter.send → 前端onmessage → fullText += ch
 * </pre>
 *
 * <h2>关键设计决策</h2>
 * <ul>
 *   <li><b>onCancel不取消任务</b>：SSE断连只dispose当前LLM订阅，ctx.cancelled不变，任务继续跑。
 *       前端重连后新SSE连接从Redis队列续读</li>
 *   <li><b>planOnce三层容错</b>：有效JSON→直接返回 / LLM输出纯文本→包装为final+output / 空→纠错重试</li>
 *   <li><b>context限制</b>：recentContext(ctx,6) 只取最近6条非系统消息，防止历史过长污染LLM prompt</li>
 *   <li><b>Mock检测</b>：execTool日志打印工具实际返回值，Executor日志对比evidence与输出，检测编造</li>
 * </ul>
 *
 * @see UnifiedAgentService 入口服务
 * @see AgentContext 对话上下文
 * @see ReactProtocol SSE事件协议
 */
class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /** 工具注册中心：管理所有可用工具的名称→描述→实现映射 */
    private final ToolRegistry tools;
    /** 工具调用器：负责实际执行工具并返回 [result, error, elapsedMs] */
    private final ToolCaller caller;
    /** 单次工具调用超时时间（毫秒），配置文件注入 */
    private final long toolTimeout;
    /** DEEP Replanner 结束策略: atLeastHalf / atLeastTwo / noLimit / evidenceBased */
    private final String finishStrategy;

    /**
     * 模式配置（不可变record）。
     * @param maxIter      最大迭代轮次（SIMPLE:10, DEEP:5）
     * @param maxToolCalls 最多工具调用次数
     * @param replanner    是否启用Replanner反思（仅DEEP为true）
     */
    public record Config(int maxIter, int maxToolCalls, boolean replanner) {}

    /** SIMPLE配置：10轮迭代, 6次工具, 不反思 */
    Config SIMPLE = new Config(10, 6, false);
    /** DEEP配置：5步调查, 6次工具, 启用Replanner反思调整 */
    Config DEEP = new Config(5, 6, true);

    AgentExecutor(ToolRegistry tools, ToolCaller caller, long toolTimeout, String finishStrategy) {
        this.tools = tools;
        this.caller = caller;
        this.toolTimeout = toolTimeout;
        this.finishStrategy = finishStrategy != null ? finishStrategy : "atLeastHalf";
    }

    // ======================== 主入口 ========================

    /**
     * 主执行入口。
     * <p>
     * 通过 {@code steps} 参数区分模式：null=SIMPLE, 非null=DEEP。
     * 使用 Reactor FluxSink 作为SSE事件发射器，所有输出（协议事件+文本token）经此流向AiTaskExecutor→Redis→前端。
     *
     * @param m        Spring AI ChatModel（LLM客户端）
     * @param ctx      对话上下文（消息列表 + 取消标志 + 工具调用计数器）
     * @param s        FluxSink，向下游发射SSE token
     * @param cfg      模式配置（exec.SIMPLE 或 exec.DEEP）
     * @param query    用户原始问题（DEEP模式需要，SIMPLE传null）
     * @param findings DEEP模式累积的发现列表（SIMPLE传null）
     * @param steps    DEEP模式的调查步骤列表（null=SIMPLE模式）
     */
    public void run(ChatModel m, AgentContext ctx, FluxSink<String> s, Config cfg,
                    String query, List<String> findings, List<String> steps) {
        // 持有当前流式订阅的引用，供onCancel调用dispose中断LLM调用
        AtomicReference<reactor.core.Disposable> sub = new AtomicReference<>();
        ctx.cancelled.set(false);
        // SSE断连时只dispose当前LLM订阅，不取消ctx（任务继续跑，前端重连后从Redis续读）
        s.onCancel(() -> {
            reactor.core.Disposable d = sub.get();
            if (d != null) d.dispose();
        });

        try {
            boolean simple = steps == null;
            if (simple) ReactProtocol.state(s, "planning", 0, null, null);
            int n = simple ? cfg.maxIter : Math.min(cfg.maxIter, steps.size());

            // ======================== 主循环 ========================
            for (int i = 0; i < n && !ctx.isCancelled(); i++) {
                int sn = i + 1; // 步骤序号，从1开始

                // ── SIMPLE: 规划 → 决策 → 行动 ──
                if (simple) {
                    // ① 调LLM单步规划（流式收集输出，每个token发[[PLAN]]事件）
                    String json = planOnce(m, ctx, sn, s);
                    if (ctx.isCancelled()) { s.complete(); return; }
                    if (json == null) break; // LLM无法产出有效JSON → 退出循环走后备路径

                    JSONObject o = JSON.parseObject(json);

                    // ② type=final: LLM认为可以直接回答
                    if ("final".equals(o.getString("type"))) {
                        // 2a. LLM在JSON的output字段里带了答案 → 直接推送（零额外LLM调用）
                        String output = o.getString("output");
                        if (output != null && !output.isBlank()) {
                            ReactProtocol.state(s, "answering", null, null, null);
                            push(s, output, ctx); // 逐字符推送，经Redis→SSE→前端
                            s.complete();
                            return;
                        }
                        // 2b. output为空 → 构建干净prompt再问LLM
                        //     跳过所有SystemMessage（含"只输出JSON"指令），保留历史+用户问题+工具结果
                        List<Message> finalMsgs = new ArrayList<>();
                        finalMsgs.add(new SystemMessage("你是智能助手, 用自然语言直接回答。回答要全面、有深度，给出具体可操作的细节。"));
                        for (Message msg : ctx.messages) {
                            if (msg instanceof SystemMessage) continue;
                            finalMsgs.add(msg);
                        }
                        ReactProtocol.state(s, "answering", null, null, null);
                        sub.set(stream(m, new Prompt(finalMsgs))
                                .takeWhile(t -> !ctx.isCancelled())
                                .subscribe(s::next, s::error, s::complete));
                        return;
                    }

                    // ③ type=tool: LLM认为需要调工具
                    if (!"tool".equals(o.getString("type"))) continue; // 无法识别的类型→跳过本轮
                    // ④ 工具调用次数达上限 → 强制要求LLM输出final
                    if (ctx.toolCalls >= cfg.maxToolCalls) {
                        ctx.addUser("已达上限,{\"type\":\"final\"}");
                        continue;
                    }
                    execTool(ctx, s, sn, o.getString("name"), o.getString("input"), json);

                // ── DEEP: 逐步骤调查（步骤计划由Supervisor制定）──
                } else {
                    String step = steps.get(i);
                    ReactProtocol.state(s, "step", sn, step, null); // 通知前端当前步骤

                    // ① 调deep_research工具收集证据
                    String toolInput = query + "|" + step;
                    execTool(ctx, s, sn, "deep_research", toolInput,
                            "{\"type\":\"tool\",\"name\":\"deep_research\",\"input\":\"" + toolInput + "\"}");

                    // ② Executor LLM分析证据（注入最近6条上下文，防止历史过长）
                    String evidence = extractLastUserContent(ctx);
                    List<Message> execMsgs = new ArrayList<>();
                    execMsgs.add(new SystemMessage(UnifiedAgentService.EXEC_PROMPT));
                    execMsgs.addAll(recentContext(ctx, 6));
                    execMsgs.add(new UserMessage(step + "\n证据:\n" + evidence
                            + "\n已完成: " + String.join(" | ", findings)));
                    String out = call(m, new Prompt(execMsgs));
                    if (out == null) out = "（执行结果为空）";
                    log.info("[Executor-步骤{}] out前100字={}",
                            sn, out.length() > 100 ? out.substring(0, 100) + "..." : out);
                    findings.add(out); // 累积发现
                    // 步骤结果仅入工具链面板（[[STATE]]），不推正文区
                    ReactProtocol.emit(s, UnifiedAgentService.S,
                            Map.of("step", sn, "state", "step_done", "text", ReactProtocol.clip(out, 200)));

                    // ③ Replanner反思（仅cfg.replanner=true且非最后一步，且至少执行2步后才允许FINISH）
                    if (cfg.replanner && i < n - 1 && !ctx.isCancelled()) {
                        String rp = call(m, new Prompt(List.of(
                                new SystemMessage(UnifiedAgentService.REPLAN_PROMPT),
                                new UserMessage(out + "\n剩余: " + steps.subList(i + 1, n)
                                        + "\n证据: " + String.join(" | ", findings)))));

                        JSONObject rl = parseJson(rp);
                        if (rl != null && "FINISH".equals(rl.getString("decision")) && canFinish(i, n)) break;
                        // ADJUST: 调整下一步计划
                        if (rl != null && "ADJUST".equals(rl.getString("decision"))
                                && rl.containsKey("adjusted_step"))
                            steps.set(i + 1, rl.getString("adjusted_step"));
                    }
                }
            }

            // ======================== 循环结束 → 最终回答 ========================
            // 到达这里的场景: 循环次数耗尽 / json==null / 工具调用上限 / DEEP步骤完成
            if (simple) {
                // SIMPLE: 替换首条SystemMessage（"只输出JSON"→纯文本指令）
                if (!ctx.messages.isEmpty() && ctx.messages.get(0) instanceof SystemMessage) {
                    ctx.messages.set(0, new SystemMessage("你是智能助手, 用自然语言直接回答。回答要全面、有深度，给出具体可操作的细节。"));
                }
            }
            Prompt finalPrompt = simple
                    ? new Prompt(ctx.messages)
                    : buildDeepFinalPrompt(findings, ctx);
            ReactProtocol.state(s, "answering", null, null, null);
            logPrompt("【最终回答Prompt】", finalPrompt);
            sub.set(stream(m, finalPrompt)
                    .takeWhile(t -> !ctx.isCancelled())
                    .subscribe(s::next, s::error, s::complete));
        } catch (Exception e) {
            log.error("[Executor] failed", e);
            if (!s.isCancelled()) s.error(e);
        }
    }

    // ======================== Replanner结束策略 ========================

    /**
     * 判断 Replanner 是否允许在当前步骤提前结束（FINISH）。
     *
     * @param i 当前步骤索引（从0开始）
     * @param n 总步骤数
     * @return true=允许 FINISH, false=必须 CONTINUE
     */
    private boolean canFinish(int i, int n) {
        int currentStep = i + 1; // 当前是第几步（从1开始）
        boolean allowed = switch (finishStrategy) {
            case "atLeastHalf" -> {
                int minRequired = (n + 1) / 2; // 向上取整
                yield currentStep >= minRequired;
            }
            case "atLeastTwo" -> currentStep >= 2;
            case "noLimit" -> true;
            case "evidenceBased" -> currentStep >= n; // 证据充分才结束，等同于必须跑完
            default -> {
                log.warn("未知的 finishStrategy: {}, 回退到 atLeastHalf", finishStrategy);
                int minRequired = (n + 1) / 2;
                yield currentStep >= minRequired;
            }
        };
        log.info("[Replanner] strategy={} step={}/{} allowed={}",
                finishStrategy, currentStep, n, allowed);
        return allowed;
    }

    // ======================== DEEP最终回答 ========================

    /**
     * 构建DEEP模式最终结论prompt。
     * <p>策略：
     * <ul>
     *   <li>分析结果中有有效数据 → 基于数据给出具体结论</li>
     *   <li>所有步骤均为"未找到相关信息" → 用通用知识直接回答，开头标注"以下为通用建议"</li>
     * </ul>
     * 注入最近6条上下文，严禁编造。
     */
    private static Prompt buildDeepFinalPrompt(List<String> findings, AgentContext ctx) {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("你是智能助手, 基于分析结果回答用户问题。\n"
                + "若分析结果中有有效信息, 基于信息给出具体回答。\n"
                + "严禁编造。回答要全面、有深度。\n分析结果:\n"
                + String.join("\n", findings)));
        msgs.addAll(recentContext(ctx, 6));
        msgs.add(new UserMessage("请回答"));
        return new Prompt(msgs);
    }

    // ======================== 工具调用 ========================

    /**
     * 执行单个工具调用。完整流程：
     * <ol>
     *   <li>校验工具名有效性</li>
     *   <li>增加工具调用计数器（ctx.toolCalls++）</li>
     *   <li>发送 [[ACT]] 事件通知前端工具调用开始</li>
     *   <li>同步调用 ToolCaller（受toolTimeout限制），返回 [result, error, elapsedMs]</li>
     *   <li>发送 [[OBS]] 事件（工具结果+耗时）</li>
     *   <li>工具JSON作为AssistantMessage + 工具结果作为UserMessage 注入上下文</li>
     *   <li>发送 [[STATE]] planning 恢复到规划状态</li>
     *   <li>打印调试日志（工具实际返回值，用于对比Executor是否编造）</li>
     * </ol>
     *
     * @param ctx   对话上下文
     * @param s     FluxSink
     * @param step  当前步骤序号
     * @param name  工具名称（如 "calculate", "deep_research"）
     * @param input 传给工具的参数
     * @param json  原始LLM JSON决策（作为AssistantMessage注入上下文）
     */
    private void execTool(AgentContext ctx, FluxSink<String> s,
                          int step, String name, String input, String json) {
        if (name == null || name.isBlank() || !tools.getAllToolNames().contains(name)) return;
        ctx.toolCalls++;
        // 通知前端：工具调用开始 → 前端工具链面板显示"动作"徽标
        ReactProtocol.emit(s, UnifiedAgentService.A,
                Map.of("step", step, "tool", name, "input", Objects.toString(input, ""), "calls", ctx.toolCalls));
        // 同步执行工具：返回数组 [result, error, elapsedMs]
        String[] r = caller.call(name, input, ctx.cancelled, toolTimeout);
        // 通知前端：工具调用结果 → 前端工具链面板显示"调用结果"徽标
        ReactProtocol.emit(s, UnifiedAgentService.O,
                ReactProtocol.obs(step, name, Long.parseLong(r[2]), r[0], r[1]));
        String toolResult = r[1] != null ? "ERR:" + r[1] : r[0];
        // 注入上下文：LLM下次规划时能看到工具调用记录和结果
        ctx.addAssistant(json); // {"type":"tool","name":"calculate","input":"987*234"}
        ctx.addUser("结果:\n" + ReactProtocol.clip(toolResult, 2000)); // 截断至2000字
        log.info("[execTool] tool={} result(length={}): {}", name, toolResult.length(),
                toolResult.length() > 300 ? toolResult.substring(0, 300) + "..." : toolResult);
        ReactProtocol.state(s, "planning", Integer.valueOf(step), null, null);
    }

    // ======================== 单步规划（SIMPLE核心） ========================

    /**
     * 调用LLM进行单步规划 → 流式收集输出 → 提取JSON决策。
     *
     * <p>LLM被指示输出以下JSON格式之一：
     * <ul>
     *   <li>{@code {"type":"final"}} — 可直接回答，无需工具</li>
     *   <li>{@code {"type":"tool","name":"工具名","input":"参数"}} — 需要调工具</li>
     * </ul>
     *
     * <h3>三层容错策略</h3>
     * <ol>
     *   <li><b>提取到有效JSON</b> → 直接返回</li>
     *   <li><b>LLM输出了纯文本</b>（如"你好！有什么可以帮你的？"）→ 包装为 {@code {"type":"final","output":"..."}} 返回</li>
     *   <li><b>输出完全为空</b> → 注入 "JSON无效" 纠错提示 → 重试一次</li>
     * </ol>
     *
     * @return JSON决策字符串，或null（已取消）
     */
    private String planOnce(ChatModel m, AgentContext ctx, int step, FluxSink<String> s) {
        // ① 流式收集LLM输出：每个token → [[PLAN]]事件(sse) + StringBuilder(extractJson)
        String raw = collect(m, new Prompt(ctx.messages), s, step, ctx.cancelled);
        // ② 从原始输出中提取首个{}包裹的JSON
        String json = ReactProtocol.extractJson(raw);
        if (json != null) return json;

        // ③ 未提取到JSON → 检查是否LLM已用纯文本直接回答（常见于问候、闲聊）
        String trimmed = raw.trim();
        if (!trimmed.isEmpty()) {
            log.info("【规划-步骤{}】LLM 未输出JSON，已包装为 final", step);
            return "{\"type\":\"final\",\"output\":" + JSON.toJSONString(trimmed) + "}";
        }

        // ④ 输出完全为空 → 注入纠错提示，重试一次
        ctx.addAssistant(raw);
        ctx.addUser("JSON无效，只输出{\"type\":\"tool/final\"}");
        if (ctx.isCancelled()) return null;
        return ReactProtocol.extractJson(collect(m, new Prompt(ctx.messages), s, step, ctx.cancelled));
    }

    // ======================== 辅助方法 ========================

    /**
     * 提取ctx.messages末尾最近{@code limit}条非SystemMessage。
     * <p>用于Executor和最终结论prompt注入上下文，限制数量防止历史过长导致LLM注意力分散。
     */
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

    /** 从ctx.messages末尾向前查找最后一条UserMessage（通常是最新的工具结果） */
    private static String extractLastUserContent(AgentContext ctx) {
        for (int i = ctx.messages.size() - 1; i >= 0; i--)
            if (ctx.messages.get(i) instanceof UserMessage)
                return ctx.messages.get(i).getContent();
        return "";
    }

    /**
     * 打印完整Prompt到日志（每条消息的角色+内容，超500字截断）。
     * 用于调试LLM输入，排查幻觉和prompt质量问题。
     */
    private static void logPrompt(String label, Prompt p) {
        if (!log.isInfoEnabled()) return;
        StringBuilder sb = new StringBuilder(label).append(":\n");
        for (Message msg : p.getInstructions()) {
            String role = msg instanceof SystemMessage ? "SYSTEM"
                    : msg instanceof UserMessage ? "USER"
                    : msg instanceof AssistantMessage ? "ASSISTANT"
                    : msg.getMessageType().name();
            String content = msg.getContent();
            if (content != null && content.length() > 500)
                content = content.substring(0, 500) + "...(truncated)";
            sb.append("  [").append(role).append("] ").append(content).append("\n");
        }
        log.info(sb.toString());
    }

    /**
     * 流式调用LLM，收集完整输出。
     * <p>每收到一个token：
     * <ul>
     *   <li>追加到本地StringBuilder → 供extractJson提取JSON</li>
     *   <li>包装为[[PLAN]]事件发射到前端 → 前端入planBuf，不显示为正文</li>
     * </ul>
     *
     * @param m    ChatModel
     * @param p    Prompt（包含ctx.messages的完整对话上下文）
     * @param s    FluxSink
     * @param step 当前步骤序号
     * @param c    取消标志（ctx.cancelled）
     * @return LLM的完整原始输出文本
     */
    private String collect(ChatModel m, Prompt p, FluxSink<String> s, int step, AtomicBoolean c) {
        logPrompt("【规划Prompt-步骤" + step + "】", p);
        StringBuilder sb = new StringBuilder();
        stream(m, p)
                .takeWhile(t -> !c.get()) // 任务取消时停止接收
                .doOnNext(ch -> {
                    sb.append(ch); // 本地累积（供extractJson使用）
                    // 每个token包装为[[PLAN]]事件 → 前端parseStep识别 → planBuf累积（不显示）
                    ReactProtocol.emit(s, UnifiedAgentService.P,
                            Map.of("step", step, "delta", ch));
                })
                .blockLast(); // 阻塞等待流完成
        return sb.toString();
    }

    /**
     * 逐字符推送文本到前端SSE流。
     * <p>每个字符作为独立FluxSink事件 → AiTaskExecutor → Redis → SSE → 前端。
     * 前端逐字符累加到fullText → 每收到一个字符触Vue响应式渲染 → renderMd(fullText)。
     *
     * @param s    FluxSink
     * @param text 要推送的文本
     * @param ctx  用于检查取消标志（ctx.cancelled）
     */
    public static void push(FluxSink<String> s, String text, AgentContext ctx) {
        if (ctx.isCancelled()) return;
        for (char ch : text.toCharArray()) {
            if (ctx.isCancelled()) return;
            s.next(String.valueOf(ch));
        }
    }

    /**
     * 从LLM原始输出中提取并解析JSON。
     * <p>先用{@link ReactProtocol#extractJson}定位{}边界，再用fastjson解析。
     * 解析失败返回null（不抛异常）。
     *
     * <p>供 runDeep Supervisor 调用（解析Supervisor返回的计划JSON）。
     */
    public static JSONObject parseJson(String raw) {
        String j = ReactProtocol.extractJson(raw);
        if (j == null) return null;
        try { return JSONObject.parseObject(j); } catch (Exception e) { return null; }
    }

    /**
     * Spring AI 流式调用封装。
     * <p>调用 {@code ChatModel.stream(Prompt)} → Reactor Flux<String>，
     * 过滤空token，下游订阅者逐token处理。
     */
    /** 阻塞调用 LLM，带空安全 */
    public static String call(ChatModel m, Prompt p) {
        try {
            var resp = m.call(p);
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) return "";
            String c = resp.getResult().getOutput().getContent();
            return c != null ? c : "";
        } catch (Exception e) { log.error("[LLM] call 失败", e); return ""; }
    }

    public static Flux<String> stream(ChatModel m, Prompt p) {
        return m.stream(p)
                .map(r -> {
                    try {
                        if (r == null) return "";
                        var result = r.getResult();
                        if (result == null) return "";
                        var output = result.getOutput();
                        if (output == null) return "";
                        String c = output.getContent();
                        return c != null ? c : "";
                    } catch (Exception e) {
                        log.warn("[LLM] stream token 解析失败: {}", e.getMessage());
                        return "";
                    }
                })
                .filter(t -> !t.isEmpty());
    }
}
