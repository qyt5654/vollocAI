-- AI 任务表
CREATE TABLE `deepai`.`ai_task` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_id`     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '任务唯一标识',
    `user_id`     BIGINT       NOT NULL DEFAULT 0 COMMENT '用户 ID',
    `query`       VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '用户查询内容',
    `intent`      VARCHAR(100) NOT NULL DEFAULT '' COMMENT '意图识别结果',
    `result`      TEXT         COMMENT '任务执行结果',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING/PROCESSING/COMPLETED/FAILED',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status_create_time` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
