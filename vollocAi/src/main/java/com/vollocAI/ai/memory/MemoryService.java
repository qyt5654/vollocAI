package com.vollocAI.ai.memory;

import com.alibaba.fastjson.JSONObject;
import com.vollocAI.ai.llm.AiUtils;
import com.vollocAI.ai.memory.model.MemorySummary;
import com.vollocAI.ai.memory.summary.FactExtractor;
import com.vollocAI.ai.memory.summary.SessionSummarizer;
import com.vollocAI.ai.model.DatabaseAi;
import com.vollocAI.ai.model.DatabaseAiService;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 记忆系统统一入口 —— 所有操作以 sessionId 为最小粒度。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    @Resource private WorkingMemoryStore workingStore;
    @Resource private LongMemoryStore longStore;
    @Resource private MemoryConfig config;
    @Resource private SessionSummarizer summarizer;
    @Resource private FactExtractor factExtractor;
    @Resource private RedissonClient redissonClient;
    @Resource private DatabaseAiService databaseAiService;

    // ═══════════════════ 加载 ═══════════════════

    public MemoryQueryResult loadContext(String sessionId, String query) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("sessionId 为空");
        }

        // Step 1: 短记忆（内部自动 Redis → DB 回退恢复）
        List<Map<String, String>> working = workingStore.loadRecent(sessionId, config.getWorkingMemoryInject());

        // Step 2: 长记忆 —— 会话维度
        // 摘要
        MemorySummary sessionSummary = longStore.getSessionSummary(sessionId);
        List<MemorySummary> summaries = sessionSummary != null ? List.of(sessionSummary) : List.of();

        // 三维度：事实型记忆，事件型记忆，偏好型记忆
        List<LongMemoryStore.ScoredFact> facts = longStore.searchFacts(sessionId, query);

        MemoryQueryResult result = new MemoryQueryResult(working, summaries, facts);

        log.debug("[Memory] loadContext sessionId={} 短记忆={} 摘要={} 事实={} tokens≈{}",
                sessionId, working.size(), summaries.size(), facts.size(), result.getEstimatedTokens());
        return result;
    }

    // ═══════════════════ 保存 ═══════════════════

    /** 每轮对话后保存短记忆到 Redis，同时触发压缩检查和增量摘要 */
    public void saveRound(String sessionId, String userMsg, String assistantMsg, int roundNum) {
        if (sessionId == null || sessionId.isEmpty()) return;
        workingStore.append(sessionId, "user", userMsg, roundNum);
        workingStore.append(sessionId, "assistant", assistantMsg, roundNum);
        maybeCompress(sessionId);

        // 每 N 轮触发一次增量摘要 + 事实提取
        int interval = config.getRoundsPerSummary();
        if (roundNum > 0 && roundNum % interval == 0) {
            maybeSummarize(sessionId);
        }
    }

    // ═══════════════════ 中间压缩 ═══════════════════

    @Async("aiThreadPool")
    public void maybeCompress(String sessionId) {
        int size = workingStore.size(sessionId);
        if (size <= config.getCompressThreshold()) return;

        RLock lock = redissonClient.getLock("compress:" + sessionId);
        try {
            if (!lock.tryLock(1, 3, TimeUnit.SECONDS)) return;
            try {
                size = workingStore.size(sessionId);
                if (size <= config.getCompressThreshold()) return;

                int keep = 5;
                List<JSONObject> all = workingStore.loadAllRaw(sessionId);
                if (all.size() <= config.getCompressThreshold()) return;

                List<JSONObject> toCompress = all.subList(0, all.size() - keep);
                List<JSONObject> recent = new ArrayList<>(all.subList(all.size() - keep, all.size()));

                String dialogue = buildDialogueText(toCompress);
                String existingSummary = findExistingSummary(all);
                String compressed = summarizeChunk(dialogue, existingSummary);

                workingStore.replaceCompressed(sessionId, compressed, recent);
                log.info("[Memory] 中间压缩完成 sessionId={} 压缩{}条→摘要{}字",
                        sessionId, toCompress.size(), compressed.length());
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ═══════════════════ 增量摘要 ═══════════════════

    /**
     * 由 saveRound 每 N 轮触发一次，异步更新增量摘要和事实提取。
     * 同一 session 的并发由 sessionId 天然保证。
     */
    @Async("aiThreadPool")
    public void maybeSummarize(String sessionId) {
        String fullDialogue = loadFullDialogue(sessionId);
        if (fullDialogue.isEmpty()) return;

        summarizer.summarize(sessionId);

        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        MemorySummary summary = longStore.getSessionSummary(sessionId);
        String summaryText = summary != null ? summary.getSummary() : "";
        factExtractor.extract(sessionId, summaryText, fullDialogue);
    }

    // ═══════════════════ 私有 ═══════════════════

    private String buildDialogueText(List<JSONObject> messages) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject m : messages) {
            String role = m.getString("role");
            String content = m.getString("content");
            if (role != null && content != null) {
                sb.append("[").append(role).append("] ").append(content).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String findExistingSummary(List<JSONObject> all) {
        for (JSONObject m : all) {
            if ("system".equals(m.getString("role"))) {
                String c = m.getString("content");
                if (c != null && c.contains("[历史对话摘要]")) {
                    return c.replace("[历史对话摘要] ", "");
                }
            }
        }
        return null;
    }

    private String summarizeChunk(String dialogue, String existingSummary) {
        ChatModel md = resolveModel();
        if (md == null || dialogue.isEmpty()) {
            return dialogue.length() > 300 ? dialogue.substring(0, 300) + "..." : dialogue;
        }

        String existingHint = existingSummary != null
                ? "已有摘要：" + existingSummary + "\n请将以下新对话合并进已有摘要。\n\n"
                : "";

        try {
            String raw = AiUtils.call(md, new Prompt(List.of(
                    new SystemMessage("你是对话摘要助手。将以下对话压缩为200字以内的渐进式摘要，"
                            + "保留关键事实和用户偏好。只输出摘要文本，不要 JSON。"),
                    new UserMessage(existingHint + "对话内容:\n" + dialogue)
            )));
            return raw != null && !raw.isBlank() ? raw.trim()
                    : (dialogue.length() > 200 ? dialogue.substring(0, 200) + "..." : dialogue);
        } catch (Exception e) {
            log.warn("[Memory] 压缩摘要 LLM 调用失败", e);
            return dialogue.length() > 200 ? dialogue.substring(0, 200) + "..." : dialogue;
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
