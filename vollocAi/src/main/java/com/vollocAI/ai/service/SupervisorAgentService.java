package com.vollocAI.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.Map;

/**
 * 多 Agent 编排 —— Supervisor → Planner → Executor → Replanner 循环。
 *
 * Supervisor: 分析用户意图，确定任务类型
 * Planner:    将任务拆解为执行步骤 [STEP1, STEP2, ...]
 * Executor:   执行 Planner 的第一步，调用工具收集证据
 * Replanner:  根据 Executor 反馈评估：CONTINUE / ADJUST / FINISH
 */
@Service
public class SupervisorAgentService {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgentService.class);
    private static final int MAX_ROUNDS = 5;
    @Resource private ToolRegistry toolRegistry;

    public String execute(String query, String apiKey, String apiUrl, String modelName,
                          List<Map<String, String>> pastMessages) {
        ChatModel model = buildModel(apiKey, apiUrl, modelName);
        StringBuilder report = new StringBuilder();
        report.append("# 任务分析报告\n\n**用户请求**: ").append(query).append("\n\n");

        // Phase 1: Supervisor 分析 → 输出任务类型和简要计划
        log.info("[Supervisor] 分析任务...");
        String supervisorOutput = model.call(new Prompt(List.of(
            new SystemMessage(SUPERVISOR_PROMPT),
            new UserMessage(query)
        ))).getResult().getOutput().getContent();
        JSONObject plan = JSON.parseObject(supervisorOutput);
        report.append("## 任务类型\n").append(plan.getString("task_type")).append("\n\n");
        report.append("## 分析计划\n").append(plan.getString("rationale")).append("\n\n");

        // Phase 2-5: Planner → Executor → Replanner 循环
        List<Message> history = new ArrayList<>();
        history.add(new AssistantMessage("分析计划：\n" + plan.getString("steps")));
        List<String> steps = parseSteps(plan);
        List<String> findings = new ArrayList<>();

        for (int round = 0; round < Math.min(MAX_ROUNDS, steps.size()); round++) {
            String step = steps.get(round);
            log.info("[Planner→Executor] 第{}步: {}", round+1, step.substring(0, Math.min(50, step.length())));

            // Planner + Executor: 执行当前步骤
            String execOutput = model.call(new Prompt(List.of(
                new SystemMessage(EXECUTOR_PROMPT),
                new UserMessage("当前步骤: " + step + "\n已完成: " + String.join("; ", findings))
            ))).getResult().getOutput().getContent();

            findings.add("步骤" + (round+1) + ": " + execOutput);
            report.append("### 步骤").append(round+1).append(": ").append(step).append("\n\n");
            report.append(execOutput).append("\n\n");

            // Replanner: 评估是否继续
            if (round < steps.size() - 1) {
                String replanOutput = model.call(new Prompt(List.of(
                    new SystemMessage(REPLANNER_PROMPT),
                    new UserMessage("步骤" + (round+1) + "完成: " + execOutput +
                            "\n剩余步骤: " + steps.subList(round+1, steps.size()) +
                            "\n已收集的证据: " + String.join(" | ", findings))
                ))).getResult().getOutput().getContent();
                JSONObject replan = JSON.parseObject(replanOutput);
                String decision = replan.getString("decision");
                log.info("[Replanner] 决定: {}", decision);
                if ("FINISH".equals(decision)) break;
                if ("ADJUST".equals(decision) && replan.containsKey("adjusted_step"))
                    steps.set(round+1, replan.getString("adjusted_step"));
            }
        }

        // Phase 6: 生成最终报告
        report.append("## 结论与建议\n\n");
        String conclusion = model.call(new Prompt(List.of(
            new SystemMessage("你是运维分析专家。根据以下调查结果，输出根因分析和处理建议（300字内）：\n" +
                    String.join("\n", findings)),
            new UserMessage("生成结论")
        ))).getResult().getOutput().getContent();
        report.append(conclusion);

        log.info("[Supervisor] 报告完成，{}个步骤，{}条发现", steps.size(), findings.size());
        return report.toString();
    }

    /** 解析 Planner 输出的步骤列表 */
    private List<String> parseSteps(JSONObject plan) {
        List<String> steps = new ArrayList<>();
        JSONArray arr = plan.getJSONArray("steps");
        if (arr != null) for (Object s : arr) steps.add(s.toString());
        if (steps.isEmpty()) steps.add(plan.getString("rationale"));
        return steps;
    }

    private ChatModel buildModel(String apiKey, String apiUrl, String modelName) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));
        OpenAiApi api = new OpenAiApi(apiUrl, apiKey,
                RestClient.builder().requestFactory(factory), WebClient.builder());
        return new OpenAiChatModel(api, OpenAiChatOptions.builder().withModel(modelName).build(),
                new org.springframework.ai.model.function.DefaultFunctionCallbackResolver(),
                List.of(), new RetryTemplate());
    }

    // ===== Agent 角色 Prompt =====

    static final String SUPERVISOR_PROMPT = """
        你是 Supervisor Agent，负责分析用户请求并制定执行计划。
        严格返回 JSON（不要任何其他文字）：
        {"task_type":"告警分析|日志排查|根因定位|日常问答","rationale":"一句话分析","steps":["步骤1","步骤2","步骤3"]}
        """;

    static final String EXECUTOR_PROMPT = "你是 Executor Agent，执行给定的调查步骤。根据已有证据推理，300字内汇报发现和关键证据。";

    static final String REPLANNER_PROMPT = """
        你是 Replanner Agent，评估当前进展。
        返回JSON: {"decision":"CONTINUE|ADJUST|FINISH","adjusted_step":"调整后的下一步(仅ADJUST时)","reason":"一句话理由"}
        证据充分时选FINISH，需要继续时选CONTINUE，方向有误时选ADJUST并给出新步骤。
        """;
}
