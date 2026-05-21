-- 模型-用户分配关系表（多对多）
CREATE TABLE `deepai`.`model_assignment` (
    `id`       BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `model_id` BIGINT NOT NULL COMMENT '模型 ID',
    `user_id`  BIGINT NOT NULL COMMENT '用户 ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_user` (`model_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
