package com.vollocAI.ai.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AiTask {
    private Long id;
    private String taskId;
    private Long userId;
    private String query;
    private String intent;
    private String result;
    private String status;  // PENDING, PROCESSING, COMPLETED, FAILED
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}
