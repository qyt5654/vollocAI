-- 会话表（长期记忆）
CREATE TABLE `deepai`.`chat_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  VARCHAR(64)  NOT NULL COMMENT '会话标识',
    `user_id`     BIGINT       NOT NULL COMMENT '用户 ID',
    `title`       VARCHAR(200) NOT NULL DEFAULT '' COMMENT '会话标题（首条消息截取）',
    `messages`    MEDIUMTEXT   COMMENT '对话记录 JSON',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
