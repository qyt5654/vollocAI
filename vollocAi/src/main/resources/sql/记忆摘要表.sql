-- 记忆摘要表：存储每个会话结束后 LLM 生成的摘要
CREATE TABLE IF NOT EXISTS `deepai`.`memory_summary` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT       NOT NULL COMMENT '用户 ID',
    `session_id`    VARCHAR(64)  NOT NULL COMMENT '来源会话 ID',
    `summary`       TEXT         NOT NULL COMMENT 'LLM 生成的摘要内容',
    `key_points`    JSON         COMMENT '关键要点列表 (JSON数组)',
    `token_count`   INT          NOT NULL DEFAULT 0 COMMENT '原始消息的大致 token 数',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_user_create_time` (`user_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT='AI 对话记忆摘要表';
