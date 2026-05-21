package com.vollocAI.ai.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.base.Preconditions;
import com.vollocAI.ai.annotation.RateLimit;
import com.vollocAI.ai.context.LoginContextHolder;
import com.vollocAI.ai.dao.ModelAssignmentDao;
import com.vollocAI.ai.entity.*;
import com.vollocAI.ai.service.AiTaskService;
import com.vollocAI.ai.service.DatabaseAiService;
import com.vollocAI.ai.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiClientController {

    @Resource private DatabaseAiService databaseAiService;
    @Resource private UserService userService;
    @Resource private RocketMQTemplate rocketMQTemplate;
    @Resource private RedisTemplate redisTemplate;
    @Resource private AiTaskService aiTaskService;
    @Resource private ModelAssignmentDao modelAssignmentDao;

    // ==================== 对话 ====================

    @RateLimit(limit = 5, duration = 1)
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody QuestionDTO questionDTO) {
        String taskId = UUID.randomUUID().toString();
        Long userId = Long.valueOf(StpUtil.getLoginIdAsString());

        AiTask aiTask = new AiTask();
        aiTask.setTaskId(taskId);
        aiTask.setUserId(userId);
        aiTask.setQuery(questionDTO.getQuestion());
        aiTask.setStatus(AiTask.STATUS_PENDING);
        aiTaskService.insert(aiTask);
        log.info("任务入库 PENDING: {}", taskId);

        AiTaskMessage msg = new AiTaskMessage();
        msg.setMessage(questionDTO.getQuestion());
        msg.setTaskId(taskId);
        msg.setUserId(userId);
        msg.setModelId(questionDTO.getId());
        rocketMQTemplate.convertAndSend("ai-task-topic", JSON.toJSONString(msg));

        return Result.ok(taskId);
    }

    @GetMapping("/result/{taskId}")
    public Result<String> getResult(@PathVariable String taskId, @RequestHeader("satoken") String token) {
        String result = (String) redisTemplate.opsForValue().get("ai:result:" + taskId);
        if (StringUtils.isNotEmpty(result)) return Result.ok(result);
        AiTask aiTask = aiTaskService.queryByTaskId(taskId);
        if (aiTask == null) return Result.ok(null);
        if (AiTask.STATUS_COMPLETED.equals(aiTask.getStatus())) return Result.ok(aiTask.getResult());
        if (AiTask.STATUS_FAILED.equals(aiTask.getStatus())) return Result.fail("任务执行失败");
        return Result.ok(null);
    }

    // ==================== 普通用户：我的模型 ====================

    @GetMapping("/selectModelByUserId")
    public Result<List<DatabaseAiDTO>> selectByUserId() {
        Long userId = LoginContextHolder.getLoginId();
        List<Long> modelIds = modelAssignmentDao.findModelIdsByUserId(userId);
        List<DatabaseAiDTO> result = new ArrayList<>();
        for (Long mid : modelIds) {
            DatabaseAi m = databaseAiService.queryById(mid);
            if (m != null) result.add(new DatabaseAiDTO(m.getId(), m.getAiApiModel()));
        }
        return Result.ok(result);
    }

    // ==================== 管理员 ====================

    private void requireAdmin() {
        Long loginId = LoginContextHolder.getLoginId();
        User user = userService.queryById(loginId);
        if (!"1".equals(user.getManager())) throw new RuntimeException("无权限");
    }

    // ---- 模型管理 ----

    @GetMapping("/admin/models")
    public Result<List<Map<String, Object>>> listAllModels() {
        requireAdmin();
        List<DatabaseAi> models = databaseAiService.selectByDatabaseAi(new DatabaseAi());
        List<Map<String, Object>> result = new ArrayList<>();
        for (DatabaseAi m : models) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", m.getId());
            item.put("aiApiModel", m.getAiApiModel());
            item.put("aiApiUrl", m.getAiApiUrl());
            item.put("assignedUserIds", modelAssignmentDao.findUserIdsByModelId(m.getId()));
            result.add(item);
        }
        return Result.ok(result);
    }

    @PostMapping("/admin/models")
    public Result addModel(@RequestBody DatabaseAi model) {
        requireAdmin();
        Preconditions.checkArgument(!StringUtils.isBlank(model.getAiApiKey()), "Key 不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(model.getAiApiUrl()), "URL 不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(model.getAiApiModel()), "模型名不能为空");
        model.setUserId(0L);
        databaseAiService.insert(model);
        return Result.ok("添加成功");
    }

    @PutMapping("/admin/models/{id}")
    public Result updateModel(@PathVariable Long id, @RequestBody DatabaseAi model) {
        requireAdmin();
        model.setId(id);
        databaseAiService.update(model);
        return Result.ok("修改成功");
    }

    @DeleteMapping("/admin/models/{id}")
    public Result deleteModel(@PathVariable Long id) {
        requireAdmin();
        modelAssignmentDao.deleteByModelId(id);
        databaseAiService.deleteById(id);
        return Result.ok("删除成功");
    }

    // ---- 分配管理 ----

    @PostMapping("/admin/assign")
    public Result assignModel(@RequestBody AssignRequest req) {
        requireAdmin();
        ModelAssignment a = new ModelAssignment();
        a.setModelId(req.modelId);
        a.setUserId(req.userId);
        modelAssignmentDao.insert(a);
        return Result.ok("分配成功");
    }

    @DeleteMapping("/admin/assign")
    public Result unassignModel(@RequestBody AssignRequest req) {
        requireAdmin();
        modelAssignmentDao.deleteByModelAndUser(req.modelId, req.userId);
        return Result.ok("已取消分配");
    }

    @GetMapping("/admin/users")
    public Result<List<User>> listUsers() {
        requireAdmin();
        List<User> users = userService.listAll();
        users.forEach(u -> u.setPassword(null));
        return Result.ok(users);
    }

    public record AssignRequest(Long modelId, Long userId) {}
}
