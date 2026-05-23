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

/**
 * 意图识别引擎 —— 用一次轻量 LLM 调用完成 text/image/voice 三分类。
 *
 * 设计思路：
 *   不是用关键词匹配（不可靠），而是让模型自己判断用户想干什么。
 *   一次 API 调用同时返回意图类别 + 处理后的内容，省去额外 NLP 步骤。
 *
 *   这个 ChatModel 是 DashScope 自动配置的轻量模型（qwen-turbo 之类），
 *   专门用于意图分类，不参与最终的内容生成。
 */
@Service
public class IntentRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(IntentRecognitionService.class);

    @Resource
    private ChatModel chatModel; // DashScope 自动注入的轻量模型

    /** System Prompt：让 LLM 充当分类器，严格输出 JSON */
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

    /**
     * 识别用户输入的意图。
     * @param query 用户原始输入
     * @return IntentResult(intent: text|image|voice, content: 提取后的内容)
     */
    public IntentResult recognize(String query) {
        // 构建 Prompt：system(分类规则) + user(用户输入)
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(INTENT_PROMPT),
                new UserMessage(query)
        ));
        // 调 LLM 拿 JSON 结果
        String json = chatModel.call(prompt).getResult().getOutput().getContent();
        logger.info("意图识别结果: {}", json);

        JSONObject obj = JSON.parseObject(json);
        String intent = obj.getString("intent");
        String content = obj.getString("content");

        // 容错：JSON 解析失败时默认为 text
        if (intent == null) {
            intent = "text";
            content = query;
        }
        return new IntentResult(intent, content);
    }

    /** 意图识别结果 */
    public record IntentResult(String intent, String content) {}
}
