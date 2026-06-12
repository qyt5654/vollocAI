-- 记忆事实表：从对话中提取的用户事实/事件/偏好，支持向量化检索
CREATE TABLE IF NOT EXISTS `deepai`.`memory_fact` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT       NOT NULL COMMENT '用户 ID',
    `session_id`    VARCHAR(64)  COMMENT '来源会话 ID（可为空表示跨会话事实）',
    `fact_type`     VARCHAR(32)  NOT NULL DEFAULT 'FACT' COMMENT '类型: FACT(事实型)/EVENT(事件型)/PREFERENCE(偏好型)',
    `fact_content`  VARCHAR(2000) NOT NULL COMMENT '事实原文',
    `embedding`     JSON         COMMENT '向量 (JSON浮点数组，MySQL回退用；实际检索走Milvus)',
    `confidence`    DECIMAL(3,2) NOT NULL DEFAULT 0.8 COMMENT '置信度 0-1',
    `source`        VARCHAR(500) COMMENT '来源依据（截取原始对话片段）',
    `tags`          VARCHAR(500) COMMENT '逗号分隔标签（偏好型记忆专用，如"详细解释,分步骤"）',
    `event_time`    DATETIME     COMMENT '事件发生时间（事件型记忆专用，用于时间衰减）',
    `decay_factor`  DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '时间衰减因子 0=无衰减',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/OUTDATED/DELETED',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_type` (`user_id`, `fact_type`),
    KEY `idx_user_status` (`user_id`, `status`),
    KEY `idx_user_tags` (`user_id`, `tags`(191))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT='AI 对话记忆事实表';
