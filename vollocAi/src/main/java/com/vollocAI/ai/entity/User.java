package com.vollocAI.ai.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * (User)实体类
 *
 * @author makejava
 * @since 2025-04-23 15:24:28
 */
@Data
public class User implements Serializable {
    /**
     * 主键
     */
    private Long id;
    /**
     * 用户名
     */
    private String username;
    /**
     * 是否管理员
     */
    private String manager;
    /**
     * 密码
     */
    private String password;

}

