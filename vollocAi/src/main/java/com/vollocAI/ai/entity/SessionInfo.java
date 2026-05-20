package com.vollocAI.ai.entity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话信息 — 线程安全的历史消息管理
 */
public class SessionInfo {
    private static final Logger logger = LoggerFactory.getLogger(SessionInfo.class);

    private static final int MAX_WINDOW_SIZE = 6;

    private final String sessionId;
    private final List<Map<String, String>> messageHistory;
    private final long createTime;
    private final ReentrantLock lock;

    public SessionInfo(String sessionId) {
        this.sessionId = sessionId;
        this.messageHistory = new ArrayList<>();
        this.createTime = System.currentTimeMillis();
        this.lock = new ReentrantLock();
    }

    public void addMessage(String userQuestion, String aiAnswer) {
        lock.lock();
        try {
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userQuestion);
            messageHistory.add(userMsg);

            Map<String, String> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", aiAnswer);
            messageHistory.add(assistantMsg);

            int maxMessages = MAX_WINDOW_SIZE * 2;
            while (messageHistory.size() > maxMessages) {
                messageHistory.remove(0);
                if (!messageHistory.isEmpty()) {
                    messageHistory.remove(0);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public List<Map<String, String>> getHistory() {
        lock.lock();
        try {
            return new ArrayList<>(messageHistory);
        } finally {
            lock.unlock();
        }
    }

    public void clearHistory() {
        lock.lock();
        try {
            messageHistory.clear();
        } finally {
            lock.unlock();
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getMessagePairCount() {
        lock.lock();
        try {
            return messageHistory.size() / 2;
        } finally {
            lock.unlock();
        }
    }
}
