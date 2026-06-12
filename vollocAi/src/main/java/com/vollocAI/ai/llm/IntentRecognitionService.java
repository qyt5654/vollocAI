package com.vollocAI.ai.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.vollocAI.ai.agent.ReactProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/** 意图识别 —— LLM 判媒介(text/image/voice) + 复杂度(simple/deep) */
@Service
public class IntentRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionService.class);

    private static final String PROMPT = """
        分析用户输入，返回 JSON（不要 markdown）：
        {"intent":"text|image|voice","content":"原始问题原文","deep":true|false}

        【content 规则】
        - content 必须是用户输入的原始文字，不要修改、不要计算、不要推理

        【intent 规则】
        - 画图/生成图片→image，朗读/语音→voice，其他→text

        【deep 规则】
        - deep=true: 需要多步分析才能回答的复杂问题。
          如: 需要查阅资料后分步解答、多角度综合分析的提问。
          判断标准: 能否一句话回答? 不能→true
        - deep=false: 简单问答、概念解释、计算、闲聊等可直接回答的问题。
          绝大多数日常提问为deep=false
        """;


    /**
     * 带记忆上下文的意图识别 —— 历史信息帮助 LLM 更准确判断 deep/simple 和意图类型。
     *
     * @param query         用户当前输入
     * @param model         LLM 模型
     * @param memoryContext 记忆上下文文本（短记忆 + 长记忆），可为 null
     */
    public IntentResult recognize(String query, ChatModel model, String memoryContext) {
        String systemPrompt = PROMPT;
        if (memoryContext != null && !memoryContext.isBlank()) {
            systemPrompt = PROMPT + "\n\n【用户历史记忆，辅助判断意图——判断这是否为新话题延续】\n" + memoryContext;
        }
        String raw = model.call(new Prompt(List.of(
                new SystemMessage(systemPrompt), new UserMessage(query)
        ))).getResult().getOutput().getContent();
        log.info("意图识别: {}", raw);
        JSONObject obj = parse(raw);
        if (obj == null) return new IntentResult("text", query, false);
        String intent = obj.getString("intent"), content = obj.getString("content");
        boolean deep = obj.getBooleanValue("deep");
        if (intent == null || intent.isBlank()) intent = "text";
        if (content == null || content.isBlank()) content = query;
        return new IntentResult(intent, content, deep);
    }

    private static JSONObject parse(String raw) {
        String json = ReactProtocol.extractJson(raw);
        if (json == null) return null;
        try { return JSON.parseObject(json); } catch (Exception e) { return null; }
    }

    public record IntentResult(String intent, String content, boolean deep) {}
}
