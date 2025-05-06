package com.vollocAI.ai.controller;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.google.common.base.Preconditions;
import com.vollocAI.ai.context.LoginContextHolder;
import com.vollocAI.ai.entity.Result;
import com.vollocAI.ai.entity.User;
import com.vollocAI.ai.service.UserService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * (User)表控制层
 *
 * @author makejava
 * @since 2025-04-23 15:24:28
 */
@RestController
@RequestMapping("user")
public class UserController {
    /**
     * 服务对象
     */
    @Resource
    private UserService userService;

    /**
     * 登录
     * @param user
     * @return
     */
    @PostMapping("doLogin")
    public Result doLogin(@RequestBody User user) {
        Preconditions.checkArgument(!StringUtils.isBlank(user.getUsername()), "用户名不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(user.getPassword()), "密码不能为空");
        //从数据库中查询数据进行比对
        Long id = userService.doLogin(user);
        if(id != null) {
            StpUtil.login(id);
            SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
            LoginContextHolder.set("loginId",id);
            System.out.println(LoginContextHolder.getLoginId());
            return Result.ok(tokenInfo);
        }
        return Result.fail("登录失败");
    }

    /**
     * 注册
     * @param user
     * @return
     */
    @PostMapping("doRegister")
    public Result doRegister(@RequestBody User user) {
        Preconditions.checkArgument(!StringUtils.isBlank(user.getUsername()), "用户名不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(user.getPassword()), "密码不能为空");
        user.setManager("0");
        userService.doRegister(user);
        return Result.ok("注册成功");
    }

    /**
     * 测试
     */
    @PostMapping("test")
    public String doo(){
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        return (String) tokenInfo.getLoginId();
    }

}

