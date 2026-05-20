package com.vollocAI.ai.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.base.Preconditions;
import com.vollocAI.ai.annotation.RateLimit;
import com.vollocAI.ai.config.AiClient;
import com.vollocAI.ai.config.AiClientNew;
import com.vollocAI.ai.context.LoginContextHolder;
import com.vollocAI.ai.entity.*;
import com.vollocAI.ai.service.AiTaskService;
import com.vollocAI.ai.service.DatabaseAiService;
import com.vollocAI.ai.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiClientController {

    @Resource
    private AiClient aiClient;
    @Resource
    private DatabaseAiService databaseAiService;
    @Resource
    private UserService userService;
    @Resource
    private AiClientNew aiClientNew;
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private RedisTemplate redisTemplate;
    @Resource
    private AiTaskService aiTaskService;

    /**
     * 获取 AI 结果：优先 Redis，兜底 DB
     */
    @GetMapping("/{taskId}")
    public Result<String> getAiAnswer(@PathVariable String taskId, @RequestHeader("satoken") String token) {
        log.info("查询任务结果 taskId:{}", taskId);

        // 1. 优先从 Redis 获取
        String result = (String) redisTemplate.opsForValue().get("ai:result:" + taskId);
        if (StringUtils.isNotEmpty(result)) {
            return Result.ok(result);
        }

        // 2. Redis 未命中，降级查 DB
        AiTask aiTask = aiTaskService.queryByTaskId(taskId);
        if (aiTask == null) {
            return Result.ok(null);
        }
        if (AiTask.STATUS_COMPLETED.equals(aiTask.getStatus())) {
            return Result.ok(aiTask.getResult());
        }
        if (AiTask.STATUS_FAILED.equals(aiTask.getStatus())) {
            return Result.fail("任务执行失败");
        }
        return Result.ok(null);
    }

    /**
     * AI 对话：落库 → 发送 MQ → 返回 taskId
     */
    @RateLimit(limit = 5, duration = 1)
    @PostMapping(value = "/ask")
    public Result<String> ask(@RequestBody QuestionDTO questionDTO) {
        String taskId = UUID.randomUUID().toString();
        Long userId = Long.valueOf(StpUtil.getLoginIdAsString());

        // 1. 落库任务（PENDING）
        AiTask aiTask = new AiTask();
        aiTask.setTaskId(taskId);
        aiTask.setUserId(userId);
        aiTask.setQuery(questionDTO.getQuestion());
        aiTask.setStatus(AiTask.STATUS_PENDING);
        aiTaskService.insert(aiTask);
        log.info("任务入库 PENDING: {}", taskId);

        // 2. 发送 MQ
        AiTaskMessage aiTaskMessage = new AiTaskMessage();
        aiTaskMessage.setMessage(questionDTO.getQuestion());
        aiTaskMessage.setTaskId(taskId);
        aiTaskMessage.setUserId(userId);
        aiTaskMessage.setModelId(questionDTO.getId());
        rocketMQTemplate.convertAndSend("ai-task-topic-message", JSON.toJSONString(aiTaskMessage));

        return Result.ok(taskId);
    }

    /**
     * AI 图片生成：落库 → 发送 MQ → 返回 taskId
     */
    @RateLimit(limit = 5, duration = 1)
    @PostMapping(value = "/askImg")
    public Result<String> askImg(@RequestBody QuestionDTO questionDTO) {
        String taskId = UUID.randomUUID().toString();
        Long userId = Long.valueOf(StpUtil.getLoginIdAsString());

        // 1. 落库任务（PENDING）
        AiTask aiTask = new AiTask();
        aiTask.setTaskId(taskId);
        aiTask.setUserId(userId);
        aiTask.setQuery(questionDTO.getQuestion());
        aiTask.setIntent("image");
        aiTask.setStatus(AiTask.STATUS_PENDING);
        aiTaskService.insert(aiTask);
        log.info("图片任务入库 PENDING: {}", taskId);

        // 2. 发送 MQ
        AiTaskMessage aiTaskMessage = new AiTaskMessage();
        aiTaskMessage.setMessage(questionDTO.getQuestion());
        aiTaskMessage.setTaskId(taskId);
        aiTaskMessage.setUserId(userId);
        aiTaskMessage.setModelId(questionDTO.getId());
        rocketMQTemplate.convertAndSend("ai-task-topic-img", JSON.toJSONString(aiTaskMessage));

        return Result.ok(taskId);
    }

    /**
     * 查询该用户所拥有的模型
     */
    @GetMapping("/selectModelByUserId")
    public Result<List<Long>> selectByUserId(@RequestHeader("satoken") String token) {
        System.out.println("前端传过来的 token 是：" + token);
        DatabaseAi databaseAi = new DatabaseAi();
        databaseAi.setUserId(LoginContextHolder.getLoginId());
        List<DatabaseAi> databaseAis = databaseAiService.selectByDatabaseAi(databaseAi);
        List<DatabaseAiDTO> modelIds = databaseAis.stream().map(databaseAiInfo -> {
            Long id = databaseAiInfo.getId();
            String aiApiModel = databaseAiInfo.getAiApiModel();
            return new DatabaseAiDTO(id, aiApiModel);
        }).toList();
        return Result.ok(modelIds);
    }

    /**
     * 插入模型
     */
    @PostMapping("/addModel")
    public Result addModel(@RequestBody DatabaseAi databaseAi) {
        Preconditions.checkArgument(!StringUtils.isBlank(databaseAi.getAiApiKey()), "AIKey不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(databaseAi.getAiApiUrl()), "Url不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(databaseAi.getAiApiModel()), "模型不能为空");
        Preconditions.checkNotNull(databaseAi.getUserId(), "所属用户不能为空");
        User user = userService.queryById(LoginContextHolder.getLoginId());
        System.out.println(user);
        if (user.getManager().equals("0")) {
            return Result.fail("没有权限");
        }
        int insert = databaseAiService.insert(databaseAi);
        if (insert > 0) {
            return Result.ok("插入成功");
        } else {
            return Result.fail("插入失败");
        }
    }
}
