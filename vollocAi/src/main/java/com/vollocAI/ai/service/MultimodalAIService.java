package com.vollocAI.ai.service;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface MultimodalAIService {

    /** 流式文本对话 */
    Flux<String> streamChat(String query, String apiKey, String apiUrl, String modelName);

    /** 流式文本对话（带历史上下文） */
    Flux<String> streamChat(String query, String apiKey, String apiUrl, String modelName,
                            List<Map<String, String>> history);

    /** 图片生成 */
    String generateImage(String query, String apiKey, String apiUrl, String modelName);

    /** 语音合成 */
    String generateVoice(String query, String apiKey, String apiUrl, String modelName);
}
