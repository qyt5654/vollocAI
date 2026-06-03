package com.vollocAI.ai.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP Client Manager — 管理所有 MCP Server 连接，自动发现工具。
 *
 * <h3>用法</h3>
 * <pre>
 *   manager.connect("sms", "python3 /path/sms-server.py");
 *   for (McpClient.McpTool tool : manager.discoverAll()) {
 *       registry.register(tool.name(), tool.description(), tool::execute);
 *   }
 * </pre>
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private final List<McpClient> clients = new CopyOnWriteArrayList<>();

    /** 连接一个 MCP Server */
    public McpClient connect(String name, String command) {
        try {
            McpClient client = new McpClient(name, command);
            clients.add(client);
            return client;
        } catch (IOException e) {
            log.error("[MCP] 连接失败 {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** 对所有已连接的 Server 执行 tools/list，返回所有发现的工具 */
    public List<McpClient.McpTool> discoverAll() {
        List<McpClient.McpTool> all = new ArrayList<>();
        for (McpClient c : clients) {
            all.addAll(c.listTools());
        }
        log.info("[MCP] 共发现 {} 个工具 ({} 个 Server)", all.size(), clients.size());
        return all;
    }

    @PreDestroy
    public void shutdown() {
        for (McpClient c : clients) {
            try { c.close(); } catch (Exception ignored) {}
        }
        log.info("[MCP] 全部连接已关闭");
    }
}
