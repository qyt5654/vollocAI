package com.vollocAI.ai.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP Client — JSON-RPC 2.0 over stdio 连接单个 MCP Server。
 * <p>用法: new McpClient("sms", "python3 /path/to/sms-server.py") → listTools() → callTool(name, args)</p>
 */
public class McpClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private final String name;
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final AtomicInteger idGen = new AtomicInteger(1);
    private final List<McpTool> tools = new ArrayList<>();

    public record McpTool(String name, String description, McpClient client) {
        public String execute(String args) { return client.callTool(name, args); }
    }

    public McpClient(String name, String command) throws IOException {
        this.name = name;
        String[] parts = command.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(parts);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        this.process = pb.start();
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        log.info("[MCP:{}] 已连接: {}", name, command);
    }

    /** 获取 Server 声明的工具列表 */
    public List<McpTool> listTools() {
        try {
            JSONObject resp = rpc("tools/list", new JSONObject());
            JSONArray arr = resp.getJSONArray("tools");
            if (arr == null) return tools;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject t = arr.getJSONObject(i);
                tools.add(new McpTool(t.getString("name"), t.getString("description"), this));
            }
            log.info("[MCP:{}] 发现 {} 个工具: {}", name, tools.size(),
                    tools.stream().map(McpTool::name).toList());
        } catch (Exception e) {
            log.error("[MCP:{}] tools/list 失败: {}", name, e.getMessage());
        }
        return tools;
    }

    /** 调用工具 */
    public String callTool(String toolName, String args) {
        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            JSONObject arguments = new JSONObject();
            arguments.put("input", args); // 大多数 MCP Server 用 "input" 字段
            arguments.put("query", args);  // 搜索类用 "query"
            params.put("arguments", arguments);

            JSONObject resp = rpc("tools/call", params);
            JSONArray content = resp.getJSONArray("content");
            if (content != null && !content.isEmpty()) {
                return content.getJSONObject(0).getString("text");
            }
            return resp.toJSONString();
        } catch (Exception e) {
            log.error("[MCP:{}] 调用 {} 失败: {}", name, toolName, e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private synchronized JSONObject rpc(String method, JSONObject params) throws Exception {
        int id = idGen.getAndIncrement();
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.put("params", params);

        writer.write(req.toJSONString() + "\n");
        writer.flush();

        // 等待响应（简易实现，生产环境应加超时）
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                JSONObject resp = JSON.parseObject(line);
                if (resp.containsKey("error")) {
                    throw new RuntimeException(resp.getJSONObject("error").getString("message"));
                }
                return resp.getJSONObject("result");
            }
            Thread.sleep(50);
        }
        throw new RuntimeException("MCP 调用超时");
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) {
                process.destroyForcibly();
            }
            log.info("[MCP:{}] 已断开", name);
        }
    }
}
