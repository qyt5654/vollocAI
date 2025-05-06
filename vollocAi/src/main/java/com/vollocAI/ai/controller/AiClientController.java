package com.vollocAI.ai.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.base.Preconditions;
import com.vollocAI.ai.config.AiClient;
import com.vollocAI.ai.config.AiClientNew;
import com.vollocAI.ai.context.LoginContextHolder;
import com.vollocAI.ai.entity.*;
import com.vollocAI.ai.service.DatabaseAiService;
import com.vollocAI.ai.service.UserService;
import com.vollocAI.ai.utils.LoginUtil;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.net.URL;
import java.util.List;

@RestController
@RequestMapping("/ai")
public class AiClientController {

    @Resource
    private AiClient aiClient;
    @Resource
    private DatabaseAiService databaseAiService;
    @Resource
    private UserService userService;
    @Resource
    private AiClientNew aiClientNew;

    /**
     * AI对话
     * @param questionDTO
     * @return
     */
    @PostMapping(value = "/ask")
    public Result<String> ask(@RequestBody QuestionDTO questionDTO) {
        DatabaseAi databaseAi = new DatabaseAi();
        databaseAi.setUserId(Long.valueOf(StpUtil.getLoginIdAsString())); // 直接使用父线程的 loginId
        databaseAi.setId(questionDTO.getId());
        List<DatabaseAi> databaseAis = databaseAiService.selectByDatabaseAi(databaseAi);

        QuestionBO questionBO = new QuestionBO();
        questionBO.setQuestion(questionDTO.getQuestion());
        questionBO.setAiApiKey(databaseAis.get(0).getAiApiKey());
        questionBO.setAiApiUrl(databaseAis.get(0).getAiApiUrl());
        questionBO.setAiApiModel(databaseAis.get(0).getAiApiModel());
        return Result.ok(aiClientNew.sendMessage(questionBO));
    }

    /**
     * AI对话生成图片
     * @param questionDTO
     * @return
     */
    @PostMapping(value = "/askImg")
    public Result<URL> askImg(@RequestBody QuestionDTO questionDTO) {
        DatabaseAi databaseAi = new DatabaseAi();
        databaseAi.setUserId(Long.valueOf(StpUtil.getLoginIdAsString())); // 直接使用父线程的 loginId
        databaseAi.setId(questionDTO.getId());
        List<DatabaseAi> databaseAis = databaseAiService.selectByDatabaseAi(databaseAi);

        QuestionBO questionBO = new QuestionBO();
        questionBO.setQuestion(questionDTO.getQuestion());
        questionBO.setAiApiKey(databaseAis.get(0).getAiApiKey());
        questionBO.setAiApiUrl(databaseAis.get(0).getAiApiUrl());
        questionBO.setAiApiModel(databaseAis.get(0).getAiApiModel());
        return Result.ok(aiClientNew.askImg(questionBO));
    }

    /**
     * 查询该用户所拥有的模型
     * @return
     */
    @GetMapping("/selectModelByUserId")
    public Result<List<Long>> selectByUserId(@RequestHeader("satoken") String token){
        System.out.println("前端传过来的 token 是：" + token);
        DatabaseAi databaseAi = new DatabaseAi();
        databaseAi.setUserId(LoginUtil.getLoginId());
        List<DatabaseAi> databaseAis = databaseAiService.selectByDatabaseAi(databaseAi);
        List<DatabaseAiDTO> modelIds = databaseAis.stream().map(databaseAiInfo ->{
            Long id = databaseAiInfo.getId();
            String aiApiModel = databaseAiInfo.getAiApiModel();
            return new DatabaseAiDTO(id, aiApiModel);
        } ).toList();
        return Result.ok(modelIds);
    }

    /**
     * 插入模型
     * @param databaseAi
     * @return
     */
    @PostMapping("/addModel")
    public Result addModel(@RequestBody DatabaseAi databaseAi){
        Preconditions.checkArgument(!StringUtils.isBlank(databaseAi.getAiApiKey()), "AIKey不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(databaseAi.getAiApiUrl()), "Url不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(databaseAi.getAiApiModel()), "模型不能为空");
        Preconditions.checkNotNull(databaseAi.getUserId(), "所属用户不能为空");
        User user = userService.queryById(LoginContextHolder.getLoginId());
        System.out.println(user);
        if(user.getManager().equals("0")){
            return Result.fail("没有权限");
        }
        int insert = databaseAiService.insert(databaseAi);
        if(insert > 0){
            return Result.ok("插入成功");
        }else{
            return Result.fail("插入失败");
        }
    }


}