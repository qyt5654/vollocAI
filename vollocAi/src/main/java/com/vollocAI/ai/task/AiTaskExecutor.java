package com.vollocAI.ai.task;

import com.vollocAI.ai.session.dao.ChatSessionDao;
import com.vollocAI.ai.session.ChatSession;
import com.vollocAI.ai.model.dao.ModelAssignmentDao;
import com.vollocAI.ai.model.DatabaseAiService;
import com.vollocAI.ai.model.DatabaseAi;
import com.vollocAI.ai.agent.UnifiedAgentService;
import com.vollocAI.ai.llm.AiUtils;
import com.vollocAI.ai.llm.IntentRecognitionService;
import com.vollocAI.ai.llm.MultimodalAIService;

import org.springframework.ai.chat.model.ChatModel;
import com.vollocAI.ai.task.AiTask;
import com.vollocAI.ai.task.AiTaskService;

import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** AI 任务执行器 —— 意图识别 → 模型凭证 → UnifiedAgent → Redis+DB 双写 */
@Service
public class AiTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiTaskExecutor.class);
    private static final int SESSION_TTL = 30, MAX_HISTORY = 20;

    @Resource private IntentRecognitionService intentRecognitionService;
    @Resource private UnifiedAgentService unifiedAgentService;
    @Resource private MultimodalAIService multimodalAIService;
    @Resource private AiTaskService aiTaskService;
    @Resource private DatabaseAiService databaseAiService;
    @Resource private ModelAssignmentDao modelAssignmentDao;
    @Resource private ChatSessionDao chatSessionDao;
    @Resource(name = "aiThreadPool") private ThreadPoolExecutor threadPool;
    @Resource private StringRedisTemplate redis;
    @Resource private RedissonClient redissonClient;

    public void execute(String taskId, String query, Long userId, Long modelId, String sessionId) {
        threadPool.execute(() -> run(taskId, query, userId, modelId, sessionId));
    }

    private void run(String taskId, String query, Long userId, Long modelId, String sessionId) {
        aiTaskService.updateStatus(taskId, AiTask.STATUS_PROCESSING);
        try {
            Cred cred = resolve(modelId, userId);
            ChatModel md = AiUtils.model(cred.key, cred.url, cred.model,120);
            IntentRecognitionService.IntentResult intent = intentRecognitionService.recognize(query, md);
            aiTaskService.update(task(taskId, intent.intent(), null, null));
            List<Map<String, String>> history = loadHistory(sessionId);
            String q = intent.content();

            switch (intent.intent()) {
                case "image" -> {
                    String a = multimodalAIService.generateImage(q, cred.key, cred.url, cred.model);
                    pushStream(taskId, a);
                    finish(taskId, sessionId, userId, query, a);
                }
                case "voice" -> {
                    String a = multimodalAIService.generateVoice(q, cred.key, cred.url, cred.model);
                    pushStream(taskId, a);
                    finish(taskId, sessionId, userId, query, a);
                }
                default -> {
                    String streamKey = "stream:" + taskId + ":q";
                    StringBuilder sb = new StringBuilder();
                    unifiedAgentService.execute(intent.deep(), q, cred.key, cred.url, cred.model, history)
                            .doOnNext(t -> {
                                redis.opsForList().rightPush(streamKey, t);
                                if (!UnifiedAgentService.isProtocolEvent(t)) sb.append(t);
                            })
                            //阻塞线程
                            .blockLast();
                    finish(taskId, sessionId, userId, query, sb.toString());
                }
            }
        } catch (Exception e) {
            log.error("任务失败 taskId:{}", taskId, e);
            fail(taskId, errMsg(e));
        }
    }

    private void pushStream(String taskId, String text) {
        if (text == null) return;
        String k = "stream:" + taskId + ":q";
        for (int i = 0; i < text.length(); i++) redis.opsForList().rightPush(k, String.valueOf(text.charAt(i)));
    }

    /**
     * 对话结束进行redis+DB双写
     * @param taskId
     * @param sessionId
     * @param userId
     * @param query
     * @param answer
     */
    private void finish(String taskId, String sessionId, Long userId, String query, String answer) {
        // 清理可能混入的协议标记和JSON决策残片，防止污染对话历史
        String clean = answer
                .replaceAll("\\[\\[(ACT|OBS|STATE|PLAN)]][^\n]*\n?", "")
                .replaceAll("\\{\"type\":\"(final|tool)\"[^}]*}", "")
                .replaceAll("JSON无效[^\n]*\n?", "")
                .trim();
        String finalAnswer = clean.isEmpty() ? answer : clean;
        saveHistory(sessionId, query, finalAnswer);
        saveSession(sessionId, userId, query);
        redis.opsForValue().set("ai:result:" + taskId, finalAnswer, 10, TimeUnit.MINUTES);
        aiTaskService.update(task(taskId, null, finalAnswer, AiTask.STATUS_COMPLETED));
    }

    /**
     * 任务失败
     * @param taskId
     * @param msg
     */
    private void fail(String taskId, String msg) {
        redis.opsForList().rightPush("stream:" + taskId + ":q", "ERROR:" + msg);
        redis.opsForValue().set("ai:result:" + taskId, msg, 10, TimeUnit.MINUTES);
        aiTaskService.update(task(taskId, null, msg, AiTask.STATUS_FAILED));
    }

    record Cred(String key, String url, String model) {}

    /**
     * 查询模型
     * @param modelId
     * @param userId
     * @return
     */
    private Cred resolve(Long modelId, Long userId) {
        DatabaseAi cfg = modelId != null && modelId != 0 ? databaseAiService.queryById(modelId) : null;
        if (cfg == null) {
            List<Long> mids = modelAssignmentDao.findModelIdsByUserId(userId);
            cfg = mids.isEmpty() ? null : databaseAiService.queryById(mids.get(0));
        }
        if (cfg == null) throw new RuntimeException("未分配模型");
        return new Cred(cfg.getAiApiKey(), cfg.getAiApiUrl(), cfg.getAiApiModel());
    }

    // ── History (unchanged) ──
    private List<Map<String, String>> loadHistory(String sid) {
        if (sid == null || sid.isEmpty()) return List.of();
        String k = "session:" + sid + ":history";
        List<String> raw = redis.opsForList().range(k, 0, -1);
        if (raw != null && !raw.isEmpty())
            return raw.stream().map(AiTaskExecutor::parseItem).filter(Objects::nonNull).toList();
        RLock lock = redissonClient.getLock("session:lock:" + sid);
        try { if (lock.tryLock(3,10,TimeUnit.SECONDS)) {
            try {
                raw = redis.opsForList().range(k,0,-1);
                if (raw != null && !raw.isEmpty()) return raw.stream().map(AiTaskExecutor::parseItem).filter(Objects::nonNull).toList();
                ChatSession s = chatSessionDao.findBySessionId(sid);
                if (s != null && s.getMessages() != null && !s.getMessages().equals("[]")) {
                    List<Map<String,String>> h = com.alibaba.fastjson.JSON.parseObject(s.getMessages(),List.class);
                    for (Map<String,String> m : h) redis.opsForList().rightPush(k, com.alibaba.fastjson.JSON.toJSONString(m));
                    redis.expire(k, SESSION_TTL, TimeUnit.MINUTES);
                    return h;
                }
            } finally { lock.unlock(); }
        }} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new ArrayList<>();
    }

    private void saveHistory(String sid, String userMsg, String answer) {
        if (sid == null || sid.isEmpty()) return;
        String k = "session:" + sid + ":history";
        RLock lock = redissonClient.getLock("session:lock:" + sid);
        try { if (lock.tryLock(2,5,TimeUnit.SECONDS)) {
            try {
                redis.opsForList().rightPush(k, com.alibaba.fastjson.JSON.toJSONString(Map.of("role","user","content",userMsg)));
                redis.opsForList().rightPush(k, com.alibaba.fastjson.JSON.toJSONString(Map.of("role","assistant","content",answer)));
                redis.expire(k, SESSION_TTL, TimeUnit.MINUTES);
                Long size = redis.opsForList().size(k);
                if (size != null && size > MAX_HISTORY) redis.opsForList().trim(k, size-MAX_HISTORY, -1);
            } finally { lock.unlock(); }
        }} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void saveSession(String sid, Long userId, String query) {
        if (sid == null || sid.isEmpty() || userId == null) return;
        try {
            ChatSession existing = chatSessionDao.findBySessionId(sid);
            List<Map<String,String>> h = loadHistory(sid);
            String json = com.alibaba.fastjson.JSON.toJSONString(h);
            String title = query.length()>40 ? query.substring(0,40) : query;
            if (existing == null) { ChatSession cs = new ChatSession(); cs.setSessionId(sid); cs.setUserId(userId); cs.setTitle(title); cs.setMessages(json); chatSessionDao.insert(cs); }
            else { existing.setTitle(title); existing.setMessages(json); chatSessionDao.update(existing); }
        } catch (Exception e) { log.warn("持久化会话失败 sessionId:{}",sid); }
    }

    private static Map<String,String> parseItem(String s) {
        if (s == null || s.isBlank()) return null;
        try { com.alibaba.fastjson.JSONObject o = com.alibaba.fastjson.JSON.parseObject(s); String r = o.getString("role"), c = o.getString("content"); return r!=null&&c!=null?Map.of("role",r,"content",c):null; }
        catch (Exception e) { return null; }
    }

    private AiTask task(String taskId, String intent, String result, String status) {
        AiTask t = new AiTask(); t.setTaskId(taskId);
        if (intent != null) t.setIntent(intent);
        if (result != null) t.setResult(result);
        if (status != null) t.setStatus(status);
        return t;
    }

    private static String errMsg(Throwable e) {
        if (e == null) return "unknown";
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0,200)+"..." : m;
    }
}
