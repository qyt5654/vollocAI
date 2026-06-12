package com.vollocAI.ai.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.vollocAI.ai.session.ChatSession;
import com.vollocAI.ai.session.dao.ChatSessionDao;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 短记忆层 —— Redis 滑动窗口 + MySQL 持久化。
 *
 * <p>热数据在 Redis List（key={@code session:{sid}:wm}），TTL 60分钟。
 * 每次写入同时异步持久化到 {@code chat_session.messages}，Redis 过期后可从 DB 恢复。</p>
 *
 * <p>单条格式：{@code {"role":"user/assistant/system","content":"...","ts":毫秒,"round":N}}</p>
 */
@Component
public class WorkingMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryStore.class);

    private static final String KEY_PREFIX = "session:";
    private static final String KEY_SUFFIX = ":wm";
    private static final int TTL_MINUTES = 60;

    @Resource private StringRedisTemplate redis;
    @Resource private RedissonClient redissonClient;
    @Resource private MemoryConfig config;
    @Resource private ChatSessionDao chatSessionDao;

    // ── Key ──

    private static String wmKey(String sessionId) { return KEY_PREFIX + sessionId + KEY_SUFFIX; }

    // ── 读 ──

    /**
     * 加载最近 n 条原始消息，返回 List<Map<role,content>>。
     * 流程：Redis key 存在则直接返回；不存在则从 DB 恢复到 Redis 再返回。
     */
    public List<Map<String, String>> loadRecent(String sessionId, int n) {
        String key = wmKey(sessionId);
        Boolean exists = redis.hasKey(key);

        if (!exists) {
            restoreFromDb(sessionId);
        }

        List<String> raw = redis.opsForList().range(key, -n, -1);
        if (raw == null || raw.isEmpty()) return List.of();
        return parseMessages(raw);
    }

    /** 加载全部短记忆（用于中间压缩） */
    public List<JSONObject> loadAllRaw(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return List.of();
        String k = wmKey(sessionId);
        List<String> raw = redis.opsForList().range(k, 0, -1);
        if (raw == null || raw.isEmpty()) return List.of();
        List<JSONObject> result = new ArrayList<>();
        for (String s : raw) {
            try { result.add(JSON.parseObject(s)); } catch (Exception ignore) {}
        }
        return result;
    }

    /** 当前消息条数 */
    public int size(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return 0;
        Long s = redis.opsForList().size(wmKey(sessionId));
        return s == null ? 0 : s.intValue();
    }

    private static List<Map<String, String>> parseMessages(List<String> raw) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String s : raw) {
            if (s == null || s.isBlank()) continue;
            try {
                JSONObject o = JSON.parseObject(s);
                String r = o.getString("role"), c = o.getString("content");
                // 跳过 system 类型的压缩摘要
                if (r != null && c != null && !"system".equals(r)) {
                    result.add(Map.of("role", r, "content", c));
                }
            } catch (Exception ignore) {}
        }
        return result;
    }

    // ── 写 ──

    /** 追加一条消息到滑动窗口 */
    public void append(String sessionId, String role, String content, int round) {
        if (sessionId == null || sessionId.isEmpty()) return;
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("ts", System.currentTimeMillis());
        msg.put("round", round);

        String k = wmKey(sessionId);
        redis.opsForList().rightPush(k, msg.toJSONString());
        redis.expire(k, TTL_MINUTES, TimeUnit.MINUTES);

        // 超出窗口大小则从头部裁剪
        int max = config.getWorkingMemorySize();
        Long size = redis.opsForList().size(k);
        if (size != null && size > max) {
            redis.opsForList().trim(k, size - max, -1);
        }
    }

    /**
     * 中间压缩后替换整体内容：压缩摘要作为第一条 system 消息 + 保留最近几条。
     * 使用分布式锁防并发写。
     */
    public void replaceCompressed(String sessionId, String compressedSummary,
                                   List<JSONObject> recentMessages) {
        if (sessionId == null || sessionId.isEmpty()) return;
        RLock lock = redissonClient.getLock("compress:" + sessionId);
        try {
            if (!lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                log.warn("[WM] 压缩锁获取失败 sessionId={}", sessionId);
                return;
            }
            try {
                String k = wmKey(sessionId);
                redis.delete(k);

                // 第一条：system 角色压缩摘要
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", "[历史对话摘要] " + compressedSummary);
                sys.put("ts", System.currentTimeMillis());
                sys.put("round", -1);  // round=-1 标记为压缩摘要
                redis.opsForList().rightPush(k, sys.toJSONString());

                // 后续：保留的最近消息
                for (JSONObject m : recentMessages) {
                    redis.opsForList().rightPush(k, m.toJSONString());
                }
                redis.expire(k, TTL_MINUTES, TimeUnit.MINUTES);
                log.info("[WM] 压缩完成 sessionId={} 摘要长度={} 保留={}条",
                        sessionId, compressedSummary.length(), recentMessages.size());
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── DB 持久化 ──

    /**
     * 将当前短记忆持久化到 MySQL {@code chat_session.messages}。
     * 每次 saveRound 后调用，保证 Redis 过期后能从 DB 恢复。
     */
    public void persistToDb(String sessionId, Long userId, String query) {
        if (sessionId == null || sessionId.isEmpty() || userId == null) return;

        // 从 Redis 读取当前全部短记忆（仅 user/assistant 角色）
        List<JSONObject> raw = loadAllRaw(sessionId);
        List<Map<String, String>> messages = new ArrayList<>();
        for (JSONObject obj : raw) {
            String role = obj.getString("role");
            String content = obj.getString("content");
            if (role != null && content != null && !"system".equals(role)) {
                messages.add(Map.of("role", role, "content", content));
            }
        }

        String json = JSON.toJSONString(messages);
        String title = query != null && query.length() > 40 ? query.substring(0, 40) : query;

        ChatSession exist = chatSessionDao.findBySessionId(sessionId);
        if (exist == null) {
            ChatSession cs = new ChatSession();
            cs.setSessionId(sessionId);
            cs.setUserId(userId);
            cs.setTitle(title);
            cs.setMessages(json);
            chatSessionDao.insert(cs);
        } else {
            exist.setTitle(title);
            exist.setMessages(json);
            chatSessionDao.update(exist);
        }
        log.debug("[WM] 持久化到 DB sessionId={} messages={}", sessionId, messages.size());
    }

    /**
     * 从 DB 恢复短记忆到 Redis（Redis 过期或冷启动时调用）。
     */
    public void restoreFromDb(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;

        ChatSession cs = chatSessionDao.findBySessionId(sessionId);
        if (cs == null || cs.getMessages() == null || cs.getMessages().equals("[]")) return;

        try {
            List<JSONObject> msgs = JSON.parseArray(cs.getMessages(), JSONObject.class);
            if (msgs == null || msgs.isEmpty()) return;

            String k = wmKey(sessionId);
            int round = 1;
            for (JSONObject m : msgs) {
                String role = m.getString("role");
                String content = m.getString("content");
                if (role == null || content == null) continue;

                JSONObject entry = new JSONObject();
                entry.put("role", role);
                entry.put("content", content);
                entry.put("ts", System.currentTimeMillis());
                entry.put("round", round);
                redis.opsForList().rightPush(k, entry.toJSONString());
                if ("user".equals(role)) round++;
            }
            redis.expire(k, TTL_MINUTES, TimeUnit.MINUTES);
            log.info("[WM] 从 DB 恢复 sessionId={} 条数={}", sessionId, msgs.size());
        } catch (Exception e) {
            log.warn("[WM] DB 恢复失败 sessionId={}", sessionId, e);
        }
    }
    /** 旧格式写入（渐进迁移期保留） */
    public void appendLegacy(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isEmpty()) return;
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        String k = KEY_PREFIX + sessionId + ":history";
        redis.opsForList().rightPush(k, msg.toJSONString());
        redis.expire(k, TTL_MINUTES, TimeUnit.MINUTES);
    }

}
