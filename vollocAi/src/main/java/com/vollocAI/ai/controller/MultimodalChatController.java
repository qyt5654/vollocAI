package com.vollocAI.ai.controller;

import com.vollocAI.ai.entity.SessionInfo;
import com.vollocAI.ai.service.IntentRecognitionService;
import com.vollocAI.ai.service.MultimodalAIService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/ai")
public class MultimodalChatController {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalChatController.class);

    @Resource
    private IntentRecognitionService intentRecognitionService;

    @Resource
    private MultimodalAIService multimodalAIService;

    private final Map<String, SessionInfo> sessionInfoMap = new ConcurrentHashMap<>();

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        SessionInfo sessionInfo = sessionInfoMap.computeIfAbsent(sessionId, SessionInfo::new);

        // 意图识别
        IntentRecognitionService.IntentResult intentResult = intentRecognitionService.recognize(request.query());

        String result = switch (intentResult.intent()) {
            case "image" -> multimodalAIService.generateImage(intentResult.content(), sessionInfo.getHistory());
            case "voice" -> multimodalAIService.generateVoice(intentResult.content(), sessionInfo.getHistory());
            default -> multimodalAIService.chat(sessionId, intentResult.content(), sessionInfo.getHistory());
        };

        // 更新会话历史
        sessionInfo.addMessage(request.query(), result);
        sessionInfoMap.put(sessionId, sessionInfo);

        logger.info("会话 {} | 意图: {} | 结果: {}", sessionId, intentResult.intent(), result);
        return Map.of(
                "sessionId", sessionId,
                "intent", intentResult.intent(),
                "result", result
        );
    }

    public record ChatRequest(String query, String sessionId) {}
}
