CREATE TABLE `deepai`.`database_ai` (
                               `id`           INT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                               `ai_api_key`   VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'AI 平台密钥',
                               `ai_api_url`   VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'AI 平台接口地址',
                               `ai_api_model` VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'AI 模型名称',
                               `user_id`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '所属用户 ID',
                               PRIMARY KEY (`id`),
                               KEY `idx_user_id` (`user_id`)                  -- 按用户查询
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;