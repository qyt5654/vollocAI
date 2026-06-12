package com.vollocAI.ai.memory.model;

import lombok.Data;
import java.time.LocalDateTime;

/** 会话摘要实体 */
@Data
public class MemorySummary {
    private Long id;
    private Long userId;
    private String sessionId;
    private String summary;
    /** JSON 数组字符串：["要点1","要点2"] */
    private String keyPoints;
    private Integer tokenCount;
    private LocalDateTime createTime;
}
