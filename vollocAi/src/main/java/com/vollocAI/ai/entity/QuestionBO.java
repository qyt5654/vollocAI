package com.vollocAI.ai.entity;

import lombok.Data;

@Data
public class QuestionBO {

    /**
     * 问题内容
     */
    private String question;
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
}
