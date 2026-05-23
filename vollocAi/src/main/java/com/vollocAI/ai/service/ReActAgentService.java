package com.vollocAI.ai.service;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ReAct Agent —— 原生 Function Calling + 流式输出。
 *
 * 不用正则解析 Prompt 文本，而是把 FunctionCallback 直接交给模型。
 * 模型在流式生成中自主决定何时调用工具——框架自动执行 → 回传结果 → 继续流式。
 * 前端看到的是 token 级实时流式 + 工具调用透明化。
 */
@Service
public class ReActAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentService.class);
    @Resource private ToolRegistry toolRegistry;

    /**
     * 流式执行 —— 原生 Function Calling + Token 级流式推送。
     * 模型自主判断是否需要工具，框架自动执行。
     */
    public Flux<String> executeStream(String query, String apiKey, String apiUrl, String modelName,
                                       List<Map<String, String>> history) {
        return Flux.defer(() -> {
            List<FunctionCallback> tools = toolRegistry.buildCallbacks(toolRegistry.getAllToolNames());
            ChatModel model = buildModel(apiKey, apiUrl, modelName, tools);

            List<Message> messages = new ArrayList<>();
            if (history != null) {
                for (Map<String, String> m : history) {
                    if ("user".equals(m.get("role")))
                        messages.add(new UserMessage(m.get("content")));
                    else if ("assistant".equals(m.get("role")))
                        messages.add(new AssistantMessage(m.get("content")));
                }
            }
            messages.add(new UserMessage(query));

            return model.stream(new Prompt(messages))
                    .map(r -> {
                        String c = r.getResult().getOutput().getContent();
                        return c != null ? c : "";
                    })
                    .filter(s -> !s.isEmpty());
        });
    }

    private ChatModel buildModel(String apiKey, String apiUrl, String modelName,
                                  List<FunctionCallback> tools) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));
        RestClient.Builder rc = RestClient.builder().requestFactory(factory);
        OpenAiApi api = new OpenAiApi(apiUrl, apiKey, rc, WebClient.builder());
        Set<String> fnNames = tools.stream().map(FunctionCallback::getName).collect(Collectors.toSet());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(modelName).withFunctions(fnNames).build();
        return new OpenAiChatModel(api, options,
                new org.springframework.ai.model.function.DefaultFunctionCallbackResolver(),
                tools, new RetryTemplate());
    }
}
