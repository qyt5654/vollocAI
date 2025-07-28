package com.vollocAI.ai.entity;

import lombok.Data;

@Data
public class AiTaskMessage {

    /**
     * 提问内容
     */
    private String message;
    /**
     * 用户Id
     */
    private Long userId;
    /**
     * 标记Id
     */
    private String taskId;
    /**
     * 模型Id
     */
    private Long modelId;

}
