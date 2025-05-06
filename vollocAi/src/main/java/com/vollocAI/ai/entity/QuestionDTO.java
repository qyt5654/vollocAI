package com.vollocAI.ai.entity;

import lombok.Data;

/**
 * 提问dto
 */
@Data
public class QuestionDTO {

    /**
     * 问题内容
     */
    private String question;

    /**
     * 模型id
     */
    private Long id;

}
