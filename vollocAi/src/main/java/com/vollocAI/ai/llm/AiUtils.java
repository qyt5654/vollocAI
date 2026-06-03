package com.vollocAI.ai.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 跨 Agent 共享的工具方法 */
public final class AiUtils {

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

    /** 截断长文本 */
    public static String abbrev(String s, int n) {
        if (s == null || s.isEmpty()) return "";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /** 构建模型（带缓存），readTimeout=60s */
    public static ChatModel model(String apiKey, String apiUrl, String modelName) {
        return model(apiKey, apiUrl, modelName, 60);
    }

    /** 构建模型（带缓存） */
    public static ChatModel model(String apiKey, String apiUrl, String modelName, int readTimeoutSec) {
        String k = apiUrl + "|" + modelName;
        return MODEL_CACHE.computeIfAbsent(k, key -> {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(java.time.Duration.ofSeconds(10));
            f.setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSec));
            OpenAiApi api = new OpenAiApi(apiUrl, apiKey, RestClient.builder().requestFactory(f), WebClient.builder());
            RetryTemplate retry = RetryTemplate.builder().maxAttempts(2).exponentialBackoff(1000, 2, 5000)
                    .retryOn(java.io.IOException.class).build();
            return new OpenAiChatModel(api, OpenAiChatOptions.builder().withModel(modelName).build(),
                    new org.springframework.ai.model.function.DefaultFunctionCallbackResolver(), List.of(), retry);
        });
    }
}
