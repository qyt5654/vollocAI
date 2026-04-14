-- 用户表
CREATE TABLE `deepai`.`user` (
                        `id`        INT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                        `username`  VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '用户名称',
                        `manager`   VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '直属上级/负责人',
                        `password`  VARCHAR(255) NOT NULL DEFAULT '' COMMENT '登录密码',
                        PRIMARY KEY (`id`),
                        KEY `idx_username` (`username`)        -- 业务查询常用
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;