package com.vollocAI.ai.service;

public interface MultimodalAIService {

    /** 文本对话 */
    String chat(String query, String apiKey, String apiUrl, String modelName);

    /** 图片生成 */
    String generateImage(String query, String apiKey, String apiUrl, String modelName);

    /** 语音合成 */
    String generateVoice(String query, String apiKey, String apiUrl, String modelName);
}
