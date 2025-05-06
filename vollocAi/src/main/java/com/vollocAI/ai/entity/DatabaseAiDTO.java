package com.vollocAI.ai.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * (DatabaseAi)DTO
 */
@Data
@AllArgsConstructor
public class DatabaseAiDTO {
    /**
     * 主键
     */
    private Long id;
    /**
     * 模型
     */
    private String aiApiModel;
}
