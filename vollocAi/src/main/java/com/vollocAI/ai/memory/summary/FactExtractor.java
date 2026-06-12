package com.vollocAI.ai.memory.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.vollocAI.ai.agent.ReactProtocol;
import com.vollocAI.ai.llm.AiUtils;
import com.vollocAI.ai.memory.LongMemoryStore;
import com.vollocAI.ai.memory.model.MemoryFact;
import com.vollocAI.ai.model.DatabaseAi;
import com.vollocAI.ai.model.DatabaseAiService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 事实提取器 —— 从对话中提取 FACT / EVENT / PREFERENCE 三种长期记忆。
 */
@Component
public class FactExtractor {

    private static final Logger log = LoggerFactory.getLogger(FactExtractor.class);

    private static final String EXTRACT_PROMPT = """
            从以下对话中提取关于用户的信息。严格返回 JSON 数组（不要 markdown）：

            [
              {
                "type": "FACT|EVENT|PREFERENCE",
                "content": "事实原文",
                "confidence": 0.9,
                "source": "截取的原始对话片段（<=30字）",
                "tags": "逗号分隔标签（仅PREFERENCE类型必填）",
                "event_time": "事件时间 ISO格式（仅EVENT类型必填，如无法确定则填null）"
              }
            ]

            【重要】一句话包含多种信息时必须拆分：
            用户说"6月1号去爬山了，吃了最喜欢的烤串"→ 拆为两条：
              {"type":"EVENT","content":"用户6月1号去爬山了","event_time":"2026-06-01T00:00:00",...}
              {"type":"PREFERENCE","content":"用户喜欢吃烤串","tags":"烤串,烧烤",...}
            用户说"我是Java开发，喜欢代码注释要详细"→ 拆为两条：
              {"type":"FACT","content":"用户职业是Java开发",...}
              {"type":"PREFERENCE","content":"用户偏好详细的代码注释","tags":"详细注释,代码规范",...}

            规则：
            - FACT：用户明确说出的客观信息，如职业、技术栈、过敏等。去掉"我"等第一人称。
            - EVENT：带时间线索的具体事件。event_time 必填（无法推断则不提取）。
            - PREFERENCE：隐含偏好/喜好，如回复风格、饮食口味、工作习惯。tags 必填。
            - 每种类型最多提取 3 条
            - confidence < 0.7 的不提取
            - 无长期价值的闲聊返回空数组 []
            """;

    @Resource private LongMemoryStore longStore;
    @Resource private DatabaseAiService databaseAiService;

    @Async("aiThreadPool")
    public void extract(String sessionId, String summary, String fullDialogue) {
        try {
            log.info("[FactExtractor] 开始提取事实 sessionId={}", sessionId);

            ChatModel md = resolveModel();
            if (md == null) { log.warn("[FactExtractor] 无可用模型"); return; }

            String dialogue = fullDialogue.length() > 4000
                    ? fullDialogue.substring(0, 4000) + "\n...(已截断)" : fullDialogue;

            String input = "对话摘要：" + summary + "\n\n完整对话:\n" + dialogue;
            String raw = AiUtils.call(md, new Prompt(List.of(
                    new SystemMessage(EXTRACT_PROMPT), new UserMessage(input))));

            List<MemoryFact> facts = parseFacts(raw, sessionId);
            if (facts.isEmpty()) {
                log.info("[FactExtractor] 未提取到有价值事实 sessionId={}", sessionId);
                return;
            }

            longStore.saveFacts(facts);
            log.info("[FactExtractor] 提取完成 sessionId={} 事实数={}", sessionId, facts.size());
        } catch (Exception e) {
            log.error("[FactExtractor] 事实提取失败 sessionId={}", sessionId, e);
        }
    }

    private List<MemoryFact> parseFacts(String raw, String sessionId) {
        List<MemoryFact> result = new ArrayList<>();
        try {
            String json = ReactProtocol.extractJson(raw);
            if (json == null) return result;

            JSONArray arr = json.trim().startsWith("[") ? JSON.parseArray(json) : null;
            if (arr == null) {
                arr = new JSONArray();
                arr.add(JSON.parseObject(json));
            }
            if (arr.isEmpty()) return result;

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String type = o.getString("type");
                String content = o.getString("content");
                Double confidence = o.getDouble("confidence");
                String source = o.getString("source");
                String tags = o.getString("tags");
                String eventTimeStr = o.getString("event_time");

                if (content == null || content.isBlank()) continue;
                if (confidence == null || confidence < 0.7) continue;

                String factType = switch (type != null ? type.toUpperCase() : "") {
                    case "EVENT" -> MemoryFact.TYPE_EVENT;
                    case "PREFERENCE" -> MemoryFact.TYPE_PREFERENCE;
                    default -> MemoryFact.TYPE_FACT;
                };

                MemoryFact mf = new MemoryFact();
                mf.setSessionId(sessionId);
                mf.setFactType(factType);
                mf.setFactContent(content);
                mf.setConfidence(Math.min(confidence, 1.0));
                mf.setSource(source != null ? source.substring(0, Math.min(source.length(), 500)) : null);
                mf.setTags(tags);
                mf.setStatus(MemoryFact.STATUS_ACTIVE);

                if (MemoryFact.TYPE_EVENT.equals(factType) && eventTimeStr != null) {
                    try {
                        LocalDateTime et = LocalDateTime.parse(eventTimeStr, fmt);
                        mf.setEventTime(et);
                        mf.setDecayFactor(MemoryFact.computeDecay(et));
                    } catch (Exception e) {
                        mf.setEventTime(LocalDateTime.now());
                        mf.setDecayFactor(0.0);
                    }
                } else {
                    mf.setDecayFactor(0.0);
                }

                result.add(mf);
            }
        } catch (Exception e) {
            log.warn("[FactExtractor] 解析失败", e);
        }
        return result;
    }

    private ChatModel resolveModel() {
        List<DatabaseAi> all = databaseAiService.selectByDatabaseAi(new DatabaseAi());
        if (all != null && !all.isEmpty())
            return AiUtils.model(all.get(0).getAiApiKey(), all.get(0).getAiApiUrl(),
                    all.get(0).getAiApiModel(), 60, 0.5, 0.7);
        return null;
    }
}
