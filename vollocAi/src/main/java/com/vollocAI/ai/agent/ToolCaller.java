package com.vollocAI.ai.agent;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 统一工具调用器 */
@Component
public class ToolCaller {
    @Resource private ToolRegistry tools;

    /** 单工具调用 */
    String[] call(String name, String input, AtomicBoolean c, long timeoutMs) {
        if (c.get()) return new String[]{"","cancelled","0"};
        long t0 = System.nanoTime();
        try {
            String o = CompletableFuture.supplyAsync(() -> tools.callTool(name, input))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS).get();
            return new String[]{o, null, Long.toString((System.nanoTime() - t0) / 1_000_000L)};
        } catch (Exception e) {
            Throwable x = e.getCause() != null ? e.getCause() : e;
            return new String[]{"", x.getMessage(), Long.toString((System.nanoTime() - t0) / 1_000_000L)};
        }
    }
}
