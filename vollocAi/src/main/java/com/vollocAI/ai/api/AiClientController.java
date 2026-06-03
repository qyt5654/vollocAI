package com.vollocAI.ai.api;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.base.Preconditions;
import com.vollocAI.ai.infra.RateLimit;
import com.vollocAI.ai.infra.LoginContextHolder;
import com.vollocAI.ai.session.dao.ChatSessionDao;
import com.vollocAI.ai.model.dao.ModelAssignmentDao;
import com.vollocAI.ai.model.DatabaseAiService;
import com.vollocAI.ai.model.DatabaseAiDTO;
import com.vollocAI.ai.user.UserService;
import com.vollocAI.ai.task.AiTaskService;
import com.vollocAI.ai.task.AiTaskExecutor;
import com.vollocAI.ai.task.AiTask;
import com.vollocAI.ai.rag.DocumentService;
import com.vollocAI.ai.model.DatabaseAi;
import com.vollocAI.ai.model.ModelAssignment;
import com.vollocAI.ai.user.User;
import com.vollocAI.ai.api.Result;
import com.vollocAI.ai.api.QuestionDTO;
import com.vollocAI.ai.session.ChatSession;
import com.vollocAI.ai.infra.ThreadPoolConfig;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/ai")
@Slf4j
public class AiClientController {

    @Resource private DatabaseAiService databaseAiService;
    @Resource private UserService userService;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private AiTaskService aiTaskService;
    @Resource private AiTaskExecutor aiTaskExecutor;
    @Resource private ModelAssignmentDao modelAssignmentDao;
    @Resource private ChatSessionDao chatSessionDao;
    @Resource private DocumentService documentService;
    @Resource(name = "aiThreadPool") private ThreadPoolExecutor aiThreadPool;

    // ==================== 对话 ====================

    /** 提交任务（异步）：落库 → 线程池执行 → 返回 taskId */
    @RateLimit(limit = 5, duration = 1)
    @PostMapping("/chat")
    public Result<String> chat(@RequestBody QuestionDTO dto) {
        StpUtil.checkLogin();
        //任务id
        String taskId = UUID.randomUUID().toString();
        Long userId = StpUtil.getLoginIdAsLong();
        AiTask t = new AiTask();
        t.setTaskId(taskId);
        t.setUserId(userId);
        t.setQuery(dto.getQuestion());
        t.setIntent("");
        t.setStatus(AiTask.STATUS_PENDING);
        //落库任务状态
        aiTaskService.insert(t);
        aiTaskExecutor.execute(taskId, dto.getQuestion(), userId, dto.getId(), dto.getSessionId());
        return Result.ok(taskId);
    }

    // ==================== SSE 流式 ====================

    /**
     * SSE 流式推送 —— 从 Redis List BLPOP 逐 token 消费，推送到前端。
     *
     * <h3>数据流</h3>
     * <pre>
     *   AgentExecutor.push() → FluxSink → AiTaskExecutor.doOnNext
     *     → redis.rightPush("stream:{taskId}:q", char)
     *       → 本方法 BLPOP 消费 → emitter.send(data:char) → 前端 EventSource
     * </pre>
     *
     * <h3>心跳策略</h3>
     * 用空 {@code data:} 事件而非 {@code comment}，浏览器EventSource只认data事件为keepalive。
     * BLPOP超时1秒无token时发心跳 + 检测失败/完成状态。
     *
     * <h3>\n 编码</h3>
     * 独立 {@code \n} token在SSE协议中被当作行分隔符吃掉，导致marked.js无法识别Markdown标题。
     * 编码为 {@code <NL>} 传输，前端解码回 {@code \n}。
     *
     * @param taskId 任务ID
     * @return SseEmitter（180秒超时）
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamResult(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        aiThreadPool.execute(() -> {
            try {
                String qKey = "stream:" + taskId + ":q";
                while (true) {
                    // BLPOP: 有数据立即返回，无数据阻塞1秒后返回null
                    String token = stringRedisTemplate.opsForList().leftPop(qKey, 1, TimeUnit.SECONDS);
                    if (token == null) {
                        // 检测任务是否已失败
                        AiTask t = aiTaskService.queryByTaskId(taskId);
                        if (t != null && AiTask.STATUS_FAILED.equals(t.getStatus())) {
                            String msg = StringUtils.isNotEmpty(t.getResult()) ? t.getResult() : "任务执行失败";
                            emitter.send(SseEmitter.event().data("ERROR:" + msg));
                            emitter.complete();
                            return;
                        }
                        // 发心跳保活（空data事件，浏览器认作keepalive）
                        emitter.send(SseEmitter.event().data(""));
                        // ai:result:{taskId} 由AiTaskExecutor.finish()写入，标志任务结束
                        if (stringRedisTemplate.opsForValue().get("ai:result:" + taskId) != null) break;
                        continue;
                    }
                    emitter.send(SseEmitter.event().data("\n".equals(token) ? "<NL>" : token));
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ==================== 结果查询（兜底） ====================

    @GetMapping("/result/{taskId}")
    public Result<String> getResult(@PathVariable String taskId, @RequestHeader("satoken") String token) {
        String r = stringRedisTemplate.opsForValue().get("ai:result:" + taskId);
        if (StringUtils.isNotEmpty(r)) return Result.ok(r);
        AiTask t = aiTaskService.queryByTaskId(taskId);
        if (t == null) return Result.ok(null);
        if (AiTask.STATUS_COMPLETED.equals(t.getStatus())) return Result.ok(t.getResult());
        if (AiTask.STATUS_FAILED.equals(t.getStatus())) return Result.fail(StringUtils.isNotEmpty(t.getResult()) ? t.getResult() : "任务执行失败");
        return Result.ok(null);
    }

    // ==================== 模型 ====================

    @GetMapping("/selectModelByUserId")
    public Result<List<DatabaseAiDTO>> selectByUserId() {
        List<Long> mids = modelAssignmentDao.findModelIdsByUserId(LoginContextHolder.getLoginId());
        List<DatabaseAiDTO> r = new ArrayList<>();
        for (Long mid : mids) { DatabaseAi m = databaseAiService.queryById(mid); if (m != null) r.add(new DatabaseAiDTO(m.getId(), m.getAiApiModel())); }
        return Result.ok(r);
    }

    // ==================== 会话 ====================

    @GetMapping("/sessions")
    public Result<List<ChatSession>> listSessions() {
        List<ChatSession> ss = chatSessionDao.findByUserId(LoginContextHolder.getLoginId());
        ss.forEach(s -> s.setMessages(null));
        return Result.ok(ss);
    }

    @GetMapping("/sessions/{sessionId}")
    public Result<ChatSession> getSession(@PathVariable String sessionId) {
        ChatSession s = chatSessionDao.findBySessionId(sessionId);
        if (s == null) return Result.fail("会话不存在");
        if (!s.getUserId().equals(StpUtil.getLoginIdAsLong())) return Result.fail("无权限");
        return Result.ok(s);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result deleteSession(@PathVariable String sessionId) {
        ChatSession s = chatSessionDao.findBySessionId(sessionId);
        if (s != null && !s.getUserId().equals(StpUtil.getLoginIdAsLong())) return Result.fail("无权限");
        chatSessionDao.deleteBySessionId(sessionId);
        return Result.ok("已删除");
    }

    // ==================== 知识库 ====================

    @GetMapping("/admin/docs/ingest")
    public Result ingestDoc(@RequestParam String content, @RequestParam(defaultValue = "") String docId) {
        requireAdmin();
        if (docId.isEmpty()) docId = UUID.randomUUID().toString();
        int count = documentService.ingest(docId, content);
        return Result.ok("入库 " + count + " 个片段");
    }

    @GetMapping("/admin/docs/search")
    public Result<List<String>> searchDocs(@RequestParam String q) {
        requireAdmin();
        return Result.ok(documentService.search(q));
    }

    // ==================== 管理员 ====================

    private void requireAdmin() {
        long loginId = StpUtil.getLoginIdAsLong(); // 自动初始化上下文 + 校验登录
        User u = userService.queryById(loginId);
        if (u == null || !"1".equals(u.getManager())) throw new RuntimeException("无权限");
    }

    @GetMapping("/admin/models")
    public Result<List<Map<String, Object>>> listAllModels() {
        requireAdmin();
        List<DatabaseAi> models = databaseAiService.selectByDatabaseAi(new DatabaseAi());
        List<Map<String, Object>> r = new ArrayList<>();
        for (DatabaseAi m : models) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", m.getId()); item.put("aiApiModel", m.getAiApiModel());
            item.put("aiApiUrl", m.getAiApiUrl());
            item.put("assignedUserIds", modelAssignmentDao.findUserIdsByModelId(m.getId()));
            r.add(item);
        }
        return Result.ok(r);
    }

    @PostMapping("/admin/models")
    public Result addModel(@RequestBody DatabaseAi m) {
        requireAdmin();
        Preconditions.checkArgument(!StringUtils.isBlank(m.getAiApiKey()), "Key 不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(m.getAiApiUrl()), "URL 不能为空");
        Preconditions.checkArgument(!StringUtils.isBlank(m.getAiApiModel()), "模型名不能为空");
        m.setUserId(0L); databaseAiService.insert(m);
        return Result.ok("添加成功");
    }

    @PutMapping("/admin/models/{id}")
    public Result updateModel(@PathVariable Long id, @RequestBody DatabaseAi m) {
        requireAdmin(); m.setId(id); databaseAiService.update(m);
        return Result.ok("修改成功");
    }

    @DeleteMapping("/admin/models/{id}")
    public Result deleteModel(@PathVariable Long id) {
        requireAdmin(); modelAssignmentDao.deleteByModelId(id); databaseAiService.deleteById(id);
        return Result.ok("删除成功");
    }

    @PostMapping("/admin/assign")
    public Result assignModel(@RequestBody AssignRequest req) {
        requireAdmin();
        ModelAssignment a = new ModelAssignment(); a.setModelId(req.modelId); a.setUserId(req.userId);
        modelAssignmentDao.insert(a);
        return Result.ok("分配成功");
    }

    @DeleteMapping("/admin/assign")
    public Result unassign(@RequestBody AssignRequest req) {
        requireAdmin();
        modelAssignmentDao.deleteByModelAndUser(req.modelId, req.userId);
        return Result.ok("已取消");
    }

    @GetMapping("/admin/users")
    public Result<List<User>> listUsers() {
        requireAdmin();
        List<User> users = userService.listAll(); users.forEach(u -> u.setPassword(null));
        return Result.ok(users);
    }

    public record AssignRequest(Long modelId, Long userId) {}
}
