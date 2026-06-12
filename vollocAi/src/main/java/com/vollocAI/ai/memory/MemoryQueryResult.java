package com.vollocAI.ai.memory;

import com.vollocAI.ai.memory.model.MemoryFact;
import com.vollocAI.ai.memory.model.MemorySummary;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索结果封装 —— 短记忆 + 长记忆合并后的结构化上下文。
 */
public class MemoryQueryResult {

    /** 短记忆：最近 N 轮原始消息 (role → content) */
    private final List<Map<String, String>> workingMessages;

    /** 长记忆：最近会话摘要 */
    private final List<MemorySummary> recentSummaries;

    /** 长记忆：语义检索召回的事实 */
    private final List<LongMemoryStore.ScoredFact> relevantFacts;

    /** 估算总 token 数 */
    private final int estimatedTokens;

    /** 是否来自中间压缩 */
    private boolean fromCompression;

    public MemoryQueryResult(List<Map<String, String>> workingMessages,
                             List<MemorySummary> recentSummaries,
                             List<LongMemoryStore.ScoredFact> relevantFacts) {
        this.workingMessages = workingMessages != null ? workingMessages : List.of();
        this.recentSummaries = recentSummaries != null ? recentSummaries : List.of();
        this.relevantFacts = relevantFacts != null ? relevantFacts : List.of();
        this.estimatedTokens = estimateTokens();
    }

    // ── getter ──

    public List<Map<String, String>> getWorkingMessages() { return workingMessages; }
    public List<MemorySummary> getRecentSummaries() { return recentSummaries; }
    public List<LongMemoryStore.ScoredFact> getRelevantFacts() { return relevantFacts; }
    public int getEstimatedTokens() { return estimatedTokens; }
    public boolean isFromCompression() { return fromCompression; }
    public void setFromCompression(boolean v) { this.fromCompression = v; }

    public boolean hasLongMemory() {
        return !recentSummaries.isEmpty() || !relevantFacts.isEmpty();
    }

    // ── 序列化 ──

    /**
     * 格式化为 SystemMessage 文本，注入 LLM 上下文。
     *
     * <p>格式：
     * <pre>
     * [系统] 关于用户的历史记忆（仅供参考，请勿在回答中复述）：
     *
     * 【近期对话摘要】
     * - 2026-06-10: 用户讨论了Python异步编程...
     *
     * 【相关记忆】
     * [事实] 用户正在开发Spring Boot项目
     * [偏好] 用户偏好详细解释和分步骤说明
     * [事件] 06-08: 用户询问过线程池优化
     * </pre>
     */
    public String toContextText() {
        if (!hasLongMemory()) return "";

        StringBuilder sb = new StringBuilder(512);
        sb.append("以下是关于用户的历史记忆，请参考但不要在回答中复述：\n");

        // 近期摘要
        if (!recentSummaries.isEmpty()) {
            sb.append("\n<近期对话>\n");
            for (MemorySummary s : recentSummaries) {
                sb.append("- ").append(shortDate(s.getCreateTime()))
                        .append(": ").append(s.getSummary()).append("\n");
            }
            sb.append("</近期对话>\n");
        }

        // 相关事实
        if (!relevantFacts.isEmpty()) {
            sb.append("\n<相关记忆>\n");
            for (LongMemoryStore.ScoredFact sf : relevantFacts) {
                MemoryFact f = sf.fact();
                String typeLabel = switch (f.getFactType()) {
                    case MemoryFact.TYPE_PREFERENCE -> "偏好";
                    case MemoryFact.TYPE_EVENT -> "事件";
                    default -> "事实";
                };
                sb.append("[").append(typeLabel).append("] ").append(f.getFactContent());
                if (f.getEventTime() != null) {
                    sb.append(" (发生时间: ").append(shortDate(f.getEventTime())).append(")");
                }
                sb.append("\n");
            }
            sb.append("</相关记忆>\n");
        }

        return sb.toString();
    }

    // ── private ──

    private int estimateTokens() {
        int tokens = 0;
        for (Map<String, String> m : workingMessages) {
            String c = m.getOrDefault("content", "");
            tokens += c.length() / 2; // 中英文混合粗略估算
        }
        for (MemorySummary s : recentSummaries) {
            tokens += s.getSummary() != null ? s.getSummary().length() / 2 : 0;
        }
        for (LongMemoryStore.ScoredFact sf : relevantFacts) {
            tokens += sf.fact().getFactContent().length() / 2;
        }
        return tokens;
    }

    private static String shortDate(java.time.LocalDateTime dt) {
        if (dt == null) return "未知";
        return dt.toLocalDate().toString();
    }
}
