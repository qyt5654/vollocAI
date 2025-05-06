package com.vollocAI.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vollocAI.ai.entity.QuestionBO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * 文本对话客户端（非流式输出）
 */
@Component
public class AiClient {

    @Value("${ai.api.stream}")
    private Boolean STREAM;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AiClient() {
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder().build();
    }

    /**
     * 发送用户提问，返回完整响应内容
     */
    public String sendMessage(QuestionBO questionBO) {
        try {
            String jsonBody = buildRequestJson(questionBO);

            String response = webClient.post()
                    .uri(questionBO.getAiApiUrl())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Authorization", "Bearer " + questionBO.getAiApiKey())
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            return extractContent(response);
        } catch (IOException e) {
            return "请求异常: " + e.getMessage();
        }
    }

    /**
     * 从 JSON 响应中提取文本内容
     */
    private String extractContent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 构建请求 JSON 字符串
     */
    private String buildRequestJson(QuestionBO questionBO) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", questionBO.getAiApiModel());
        requestBody.put("stream", false); // 明确禁用流式
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", questionBO.getQuestion()));
        requestBody.put("messages", messages);
        return objectMapper.writeValueAsString(requestBody);
    }
}
