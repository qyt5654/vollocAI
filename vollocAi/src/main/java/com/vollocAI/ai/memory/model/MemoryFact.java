package com.vollocAI.ai.memory.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 记忆事实实体 —— 支持三种长期记忆类型。
 *
 * <ul>
 *   <li><b>FACT（事实型）</b>：用户明确提供的信息，如"我对花生过敏"。
 *       以 KV 为主存储，向量检索做冗余备份。</li>
 *   <li><b>EVENT（事件型）</b>：带时间戳的对话事件，如"上周投诉了登录问题"。
 *       文本记录 + 时间衰减因子，较旧的事件检索权重降低。</li>
 *   <li><b>PREFERENCE（偏好型）</b>：用户隐含偏好，如"每次都要求详细解释"。
 *       标签系统为主（向量检索效果差），搭配 KV 快速匹配。</li>
 * </ul>
 */
@Data
public class MemoryFact {
    private Long id;
    private Long userId;
    private String sessionId;

    /** FACT / EVENT / PREFERENCE */
    private String factType;

    private String factContent;

    /** JSON 浮点数组，MySQL 回退检索用；实际检索走 Milvus */
    private String embedding;

    /** 0-1 置信度 */
    private Double confidence;

    /** 来源：截取的原始对话片段 */
    private String source;

    /** 逗号分隔的标签，用于偏好型记忆的快速匹配（如 "详细解释,代码示例,分步骤"） */
    private String tags;

    /** 事件发生时间（事件型记忆专用），用于时间衰减计算 */
    private LocalDateTime eventTime;

    /** 时间衰减因子：0=无衰减, 1=完全衰减。由事件发生时间计算得出 */
    private Double decayFactor;

    /** ACTIVE / OUTDATED / DELETED */
    private String status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ── 常量 ──

    public static final String TYPE_FACT       = "FACT";
    public static final String TYPE_EVENT      = "EVENT";
    public static final String TYPE_PREFERENCE = "PREFERENCE";

    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_OUTDATED = "OUTDATED";
    public static final String STATUS_DELETED  = "DELETED";

    /**
     * 根据事件发生时间计算时间衰减因子。
     *
     * <p>公式：衰减 = 1 - e^{−λ·Δt}，其中 Δt 为距今天数。λ=0.05 时，14天后衰减约50%。</p>
     *
     * @param eventTime 事件发生时间，null 表示无衰减
     * @return 0~1 之间，0 表示无衰减
     */
    public static double computeDecay(LocalDateTime eventTime) {
        if (eventTime == null) return 0.0;
        long days = java.time.Duration.between(eventTime, LocalDateTime.now()).toDays();
        if (days <= 0) return 0.0;
        double lambda = 0.05;
        double decay = 1.0 - Math.exp(-lambda * days);
        return Math.min(decay, 1.0);
    }
}
