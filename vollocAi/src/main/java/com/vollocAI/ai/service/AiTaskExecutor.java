package com.vollocAI.ai.service;

import com.vollocAI.ai.dao.ChatSessionDao;
import com.vollocAI.ai.dao.ModelAssignmentDao;
import com.vollocAI.ai.entity.*;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 任务执行器 —— 替代 MQ 消费者，线程池异步处理。
 *
 * 流程：意图识别 → 模型凭证 → 历史加载 → 路由执行 → Redis+DB 双写
 */
@Service
public class AiTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiTaskExecutor.class);
    private static final String SESSION_KEY = "session:%s:history";
    private static final int SESSION_TTL = 30;
    private static final int MAX_HISTORY = 20;

    @Resource private IntentRecognitionService intentRecognitionService;
    @Resource private MultimodalAIService multimodalAIService;
    @Resource private ReActAgentService reActAgentService;
    @Resource private SupervisorAgentService supervisorAgentService;
    @Resource private AiTaskService aiTaskService;
    @Resource private DatabaseAiService databaseAiService;
    @Resource private ModelAssignmentDao modelAssignmentDao;
    @Resource private ChatSessionDao chatSessionDao;
    @Resource(name = "aiThreadPool") private ThreadPoolExecutor threadPool;
    @Resource private RedisTemplate redisTemplate;
    @Resource private RedissonClient redissonClient;

    /** 异步执行任务 */
    public void execute(String taskId, String query, Long userId, Long modelId, String sessionId) {
        threadPool.execute(() -> {
            //更新任务状态
            aiTaskService.updateStatus(taskId, AiTask.STATUS_PROCESSING);
            try {
                // 1. 意图识别
                var intent = intentRecognitionService.recognize(query);
                //更新任务信息
                aiTaskService.update(make(taskId, intent.intent(), null, null));

                // 2. 模型凭证
                var cred = resolveCredentials(modelId, userId);
                // 3. 历史
                var history = loadHistory(sessionId);

                // 4. 路由执行
                String answer = switch (intent.intent()) {
                    case "image" -> multimodalAIService.generateImage(intent.content(), cred.key, cred.url, cred.model);
                    case "voice" -> multimodalAIService.generateVoice(intent.content(), cred.key, cred.url, cred.model);
                    default -> {
                        String q = intent.content();
                        if (contains(q, "根因", "报告", "总结", "复盘", "综合", "全面", "排查"))
                            yield supervisorAgentService.execute(q, cred.key, cred.url, cred.model, history);
                        else {
                            // 原生 Function Calling + 流式：token 实时入队 → SSE → 前端逐字渲染
                            StringBuilder sb = new StringBuilder();
                            reActAgentService.executeStream(q, cred.key, cred.url, cred.model, history)
                                .doOnNext(token -> {
                                    sb.append(token);
                                    redisTemplate.opsForList().rightPush("stream:" + taskId + ":q", token);
                                })
                                .doOnComplete(() -> finish(taskId, sessionId, userId, query, sb.toString()))
                                .doOnError(e -> {
                                    log.error("ReAct流式失败 taskId:{}", taskId, e);
                                    aiTaskService.updateStatus(taskId, AiTask.STATUS_FAILED);
                                })
                                .subscribe();
                            yield null;
                        }
                    }
                };
                // 流式推送：将结果逐字写入 Redis List，前端 SSE 逐字渲染
                if (answer != null) {
                    for (char c : answer.toCharArray())
                        redisTemplate.opsForList().rightPush("stream:" + taskId + ":q", String.valueOf(c));
                    finish(taskId, sessionId, userId, query, answer);
                }

            } catch (Exception e) {
                log.error("任务失败 taskId:{}", taskId, e);
                aiTaskService.updateStatus(taskId, AiTask.STATUS_FAILED);
            }
        });
    }

    private void finish(String taskId, String sessionId, Long userId, String query, String answer) {
        saveHistory(sessionId, query, answer);
        saveSessionToDb(sessionId, userId, query);
        redisTemplate.opsForValue().set("ai:result:" + taskId, answer, 10, TimeUnit.MINUTES);
        aiTaskService.update(make(taskId, null, answer, AiTask.STATUS_COMPLETED));
        log.info("任务完成 taskId:{} len:{}", taskId, answer.length());
    }

    // ---- 辅助方法 ----

    record Cred(String key, String url, String model) {}

    private Cred resolveCredentials(Long modelId, Long userId) {
        if (modelId != null && modelId != 0) {
            DatabaseAi cfg = databaseAiService.queryById(modelId);
            if (cfg != null) return new Cred(cfg.getAiApiKey(), cfg.getAiApiUrl(), cfg.getAiApiModel());
        }
        // 取用户第一个分配的模型兜底
        List<Long> mids = modelAssignmentDao.findModelIdsByUserId(userId);
        if (!mids.isEmpty()) {
            DatabaseAi cfg = databaseAiService.queryById(mids.get(0));
            if (cfg != null) return new Cred(cfg.getAiApiKey(), cfg.getAiApiUrl(), cfg.getAiApiModel());
        }
        throw new RuntimeException("未分配模型，请联系管理员分配后再对话");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> loadHistory(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return List.of();
        String key = String.format(SESSION_KEY, sessionId);
        // 1. Redis List
        List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw != null && !raw.isEmpty())
            return raw.stream().map(o -> (Map<String, String>) o).toList();
        // 2. DB 兜底：分布式锁保证只有一个线程做恢复，其他等锁释放后重读 Redis
        RLock lock = redissonClient.getLock("session:lock:" + sessionId);
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                try {
                    // 双重检查：等锁期间其他线程可能已恢复完成
                    raw = redisTemplate.opsForList().range(key, 0, -1);
                    if (raw != null && !raw.isEmpty())
                        return raw.stream().map(o -> (Map<String, String>) o).toList();
                    // 从 DB 恢复
                    ChatSession s = chatSessionDao.findBySessionId(sessionId);
                    if (s != null && s.getMessages() != null && !s.getMessages().equals("[]")) {
                        List<Map<String, String>> h = com.alibaba.fastjson.JSON.parseObject(s.getMessages(), List.class);
                        for (Map<String, String> m : h) redisTemplate.opsForList().rightPush(key, m);
                        redisTemplate.expire(key, SESSION_TTL, TimeUnit.MINUTES);
                        return h;
                    }
                } finally { lock.unlock(); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new ArrayList<>();
    }

    private void saveHistory(String sessionId, String userMsg, String answer) {
        if (sessionId == null || sessionId.isEmpty()) return;
        String key = String.format(SESSION_KEY, sessionId);
        RLock lock = redissonClient.getLock("session:lock:" + sessionId);
        try {
            if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                try {
                    redisTemplate.opsForList().rightPush(key, Map.of("role", "user", "content", userMsg));
                    redisTemplate.opsForList().rightPush(key, Map.of("role", "assistant", "content", answer));
                    redisTemplate.expire(key, SESSION_TTL, TimeUnit.MINUTES);
                    Long size = redisTemplate.opsForList().size(key);
                    if (size != null && size > MAX_HISTORY) {
                        redisTemplate.opsForList().trim(key, size - MAX_HISTORY, -1);
                    }
                } finally { lock.unlock(); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void saveSessionToDb(String sessionId, Long userId, String query) {
        if (sessionId == null || sessionId.isEmpty() || userId == null) return;
        try {
            ChatSession existing = chatSessionDao.findBySessionId(sessionId);
            List<Map<String, String>> h = loadHistory(sessionId);
            String json = com.alibaba.fastjson.JSON.toJSONString(h);
            String title = query.length() > 40 ? query.substring(0, 40) : query;
            if (existing == null) {
                ChatSession cs = new ChatSession(); cs.setSessionId(sessionId);
                cs.setUserId(userId); cs.setTitle(title); cs.setMessages(json);
                chatSessionDao.insert(cs);
            } else { existing.setTitle(title); existing.setMessages(json); chatSessionDao.update(existing); }
        } catch (Exception e) { log.warn("持久化会话失败 sessionId:{}", sessionId); }
    }

    private AiTask make(String taskId, String intent, String result, String status) {
        AiTask t = new AiTask();
        t.setTaskId(taskId);
        if (intent != null) t.setIntent(intent);
        if (result != null) t.setResult(result);
        if (status != null) t.setStatus(status);
        return t;
    }

    private static boolean contains(String s, String... kws) {
        for (String kw : kws) if (s.contains(kw)) return true;
        return false;
    }
}
