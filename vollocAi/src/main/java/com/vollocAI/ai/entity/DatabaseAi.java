package com.vollocAI.ai.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * (DatabaseAi)实体类
 *
 * @author makejava
 * @since 2025-04-23 15:03:00
 */
@Data
public class DatabaseAi implements Serializable {
    /**
     * 主键
     */
    private Long id;
    /**
     * AIKey值
     */
    private String aiApiKey;
    /**
     * AIURL值
     */
    private String aiApiUrl;
    /**
     * 模型
     */
    private String aiApiModel;
    /**
     * 所属用户名
     */
    private Long userId;

}

