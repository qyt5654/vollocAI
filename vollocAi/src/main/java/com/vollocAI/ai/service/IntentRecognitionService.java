package com.vollocAI.ai.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class IntentRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionService.class);

    @Resource
    private ChatModel chatModel;

    private static final String INTENT_PROMPT = """
        将用户输入分类为以下三种意图之一，返回标准 JSON：

        可选意图（严格三选一）：
        - "text"  : 问答、聊天、计算、翻译等纯文本交互
        - "image" : 绘画、生成图片、可视化等需求
        - "voice" : 语音合成、朗读、播报等需求

        输出格式（必须严格遵守）：
        {"intent":"text|image|voice","content":"处理后的内容"}

        示例：
        用户：画一只猫 -> {"intent":"image","content":"一只可爱的猫"}
        用户：你好 -> {"intent":"text","content":"你好"}
        用户：念一段话 -> {"intent":"voice","content":"大家好"}
        用户：1+1等于几 -> {"intent":"text","content":"1+1等于几"}
        """;

    public IntentResult recognize(String query) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(INTENT_PROMPT),
                new UserMessage(query)
        ));
        String json = chatModel.call(prompt).getResult().getOutput().getContent();
        logger.info("意图识别结果: {}", json);

        JSONObject obj = JSON.parseObject(json);
        String intent = obj.getString("intent");
        String content = obj.getString("content");

        if (intent == null) {
            intent = "text";
            content = query;
        }

        return new IntentResult(intent, content);
    }

    public record IntentResult(String intent, String content) {}
}
