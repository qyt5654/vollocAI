package com.vollocAI.ai.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Agent 对话上下文 + 计数器 */
public class AgentContext {
    final List<Message> messages;
    final AtomicBoolean cancelled;
    int steps, toolCalls;

    AgentContext(List<Message> initial) {
        this.messages = new ArrayList<>(initial);
        this.cancelled = new AtomicBoolean(false);
    }

    void addAssistant(String text) { messages.add(new AssistantMessage(text)); }
    void addUser(String text) { messages.add(new UserMessage(text)); }
    boolean isCancelled() { return cancelled.get(); }
    void cancel() { cancelled.set(true); }
}
