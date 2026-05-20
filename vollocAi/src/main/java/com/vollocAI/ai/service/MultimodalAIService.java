package com.vollocAI.ai.service;

import java.util.List;
import java.util.Map;

public interface MultimodalAIService {

    /**
     * 文本对话（带会话上下文记忆）
     */
    String chat(String sessionId, String query, List<Map<String, String>> history);

    /**
     * 图片生成
     */
    String generateImage(String query, List<Map<String, String>> history);

    /**
     * 语音合成
     */
    String generateVoice(String query, List<Map<String, String>> history);
}
