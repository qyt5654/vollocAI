package com.vollocAI.ai.llm;

import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import com.vollocAI.ai.agent.ReactProtocol;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 跨 Agent 共享的工具方法 */
public final class AiUtils {

    private static final Logger log = LoggerFactory.getLogger(AiUtils.class);
    private static final Map<String, ChatModel> MODEL_CACHE = new ConcurrentHashMap<>();

    /** List<Map> → List<Message> */
    public static List<Message> toMessages(List<Map<String, String>> h) {
        if (h == null) return new ArrayList<>();
        List<Message> l = new ArrayList<>();
        for (Map<String, String> m : h) {
            if ("user".equals(m.get("role"))) l.add(new UserMessage(m.get("content")));
            else if ("assistant".equals(m.get("role"))) l.add(new AssistantMessage(m.get("content")));
        }
        return l;
    }

    /** 构建模型（带缓存） */
    public static ChatModel model(String apiKey, String apiUrl, String modelName, int readTimeoutSec, Double temperature, Double topP) {
        String k = apiUrl + "|" + modelName;
        return MODEL_CACHE.computeIfAbsent(k, key -> {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(java.time.Duration.ofSeconds(10));
            f.setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSec));
            OpenAiApi api = new OpenAiApi(apiUrl, apiKey, RestClient.builder().requestFactory(f), WebClient.builder());
            RetryTemplate retry = RetryTemplate.builder().maxAttempts(2).exponentialBackoff(1000, 2, 5000)
                    .retryOn(java.io.IOException.class).build();
            return new OpenAiChatModel(
                    api,
                    OpenAiChatOptions.builder().model(modelName).temperature(temperature).topP(topP).build(),
                    new org.springframework.ai.model.function.DefaultFunctionCallbackResolver(),
                    List.of(),
                    retry
            );
        });
    }

    // ======================== LLM 调用 ========================

    /** 阻塞调用 LLM，带空安全 */
    public static String call(ChatModel m, Prompt p) {
        try {
            var resp = m.call(p);
            if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) return "";
            String c = resp.getResult().getOutput().getContent();
            return c != null ? c : "";
        } catch (Exception e) { log.error("[LLM] call 失败", e); return ""; }
    }

    /** 流式调用 LLM，过滤空 token */
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

    // ======================== JSON ========================

    /**
     * 从 LLM 原始输出中提取并解析 JSON。
     * 先用 {@link ReactProtocol#extractJson} 定位 {} 边界，再用 fastjson 解析。
     * 解析失败返回 null（不抛异常）。
     */
    public static JSONObject parseJson(String raw) {
        String j = ReactProtocol.extractJson(raw);
        if (j == null) return null;
        try { return JSONObject.parseObject(j); } catch (Exception e) { return null; }
    }

    // ======================== 日志 ========================

    /**
     * 打印完整 Prompt 到日志（每条消息的角色+内容，超500字截断）。
     */
    public static void logPrompt(String label, Prompt p) {
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

    private AiUtils() {}
}
