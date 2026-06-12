package com.vollocAI.ai.memory.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.vollocAI.ai.llm.AiUtils;
import com.vollocAI.ai.memory.LongMemoryStore;
import com.vollocAI.ai.memory.WorkingMemoryStore;
import com.vollocAI.ai.model.DatabaseAi;
import com.vollocAI.ai.model.DatabaseAiService;
import com.vollocAI.ai.session.ChatSession;
import com.vollocAI.ai.session.dao.ChatSessionDao;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话摘要生成器 —— 异步 LLM 对会话生成结构化摘要。
 */
@Component
public class SessionSummarizer {

    private static final Logger log = LoggerFactory.getLogger(SessionSummarizer.class);

    private static final String SUMMARY_PROMPT = """
            总结以下对话的核心内容，返回 JSON（不要 markdown）：
            {"summary":"200字以内的对话摘要","key_points":["要点1","要点2","要点3"]}

            规则：
            - summary 聚焦于：用户讨论了什么主题、得出了什么结论、用户透露了什么信息
            - key_points 提取 2-5 个可独立理解的关键事实
            - 只提取用户明确表达的信息，不要推测
            - 如果对话太短或没有实质内容，返回 {"summary":"简短闲聊","key_points":[]}
            """;

    @Resource private WorkingMemoryStore workingStore;
    @Resource private LongMemoryStore longStore;
    @Resource private ChatSessionDao chatSessionDao;
    @Resource private DatabaseAiService databaseAiService;

    @Async("aiThreadPool")
    public void summarize(String sessionId) {
        try {
            log.info("[Summarizer] 开始生成摘要 sessionId={}", sessionId);
            String dialogue = loadFullDialogue(sessionId);
            if (dialogue.isEmpty()) { log.info("[Summarizer] 对话为空，跳过"); return; }

            ChatModel md = resolveModel();
            if (md == null) { log.warn("[Summarizer] 无可用模型"); return; }

            String raw = AiUtils.call(md, new Prompt(List.of(
                    new SystemMessage(SUMMARY_PROMPT), new UserMessage(dialogue))));

            JSONObject obj = AiUtils.parseJson(raw);
            String summary = obj != null ? obj.getString("summary") : null;
            JSONArray kpArr = obj != null ? obj.getJSONArray("key_points") : null;
            List<String> keyPoints = kpArr != null
                    ? kpArr.stream().map(Object::toString).toList() : List.of();

            if (summary == null || summary.isBlank()) {
                summary = dialogue.length() > 200 ? dialogue.substring(0, 200) + "..." : dialogue;
            }

            longStore.saveSummary(sessionId, summary, keyPoints, dialogue.length() / 2);
            log.info("[Summarizer] 摘要完成 sessionId={} summaryLen={} keyPoints={}",
                    sessionId, summary.length(), keyPoints.size());
        } catch (Exception e) {
            log.error("[Summarizer] 摘要生成失败 sessionId={}", sessionId, e);
        }
    }

    private String loadFullDialogue(String sessionId) {
        var raw = workingStore.loadAllRaw(sessionId);
        if (!raw.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var m : raw) {
                String role = m.getString("role"), content = m.getString("content");
                if (role != null && content != null && !"system".equals(role))
                    sb.append("[").append(role).append("] ").append(content).append("\n");
            }
            return sb.toString().trim();
        }
        ChatSession cs = chatSessionDao.findBySessionId(sessionId);
        if (cs != null && cs.getMessages() != null && !"[]".equals(cs.getMessages())) {
            try {
                List<JSONObject> msgs = JSON.parseArray(cs.getMessages(), JSONObject.class);
                StringBuilder sb = new StringBuilder();
                for (var m : msgs)
                    sb.append("[").append(m.getString("role")).append("] ").append(m.getString("content")).append("\n");
                return sb.toString().trim();
            } catch (Exception ignore) {}
        }
        return "";
    }

    private ChatModel resolveModel() {
        List<DatabaseAi> all = databaseAiService.selectByDatabaseAi(new DatabaseAi());
        if (all != null && !all.isEmpty())
            return AiUtils.model(all.get(0).getAiApiKey(), all.get(0).getAiApiUrl(),
                    all.get(0).getAiApiModel(), 60, 0.5, 0.7);
        return null;
    }
}
