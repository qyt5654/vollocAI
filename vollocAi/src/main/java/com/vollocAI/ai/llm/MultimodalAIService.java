package com.vollocAI.ai.llm;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface MultimodalAIService {

    /** 图片生成 */
    String generateImage(String query, String apiKey, String apiUrl, String modelName);

    /** 语音合成 */
    String generateVoice(String query, String apiKey, String apiUrl, String modelName);
}
