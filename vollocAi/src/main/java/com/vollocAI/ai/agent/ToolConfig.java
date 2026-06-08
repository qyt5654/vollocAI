package com.vollocAI.ai.agent;

import com.vollocAI.ai.llm.McpSearchService;
import com.vollocAI.ai.mcp.McpClient;
import com.vollocAI.ai.mcp.McpClientManager;
import com.vollocAI.ai.rag.DocumentService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具注册 —— 显式注册，只有这里列出的才暴露给 AI。
 */
@Configuration
public class ToolConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolConfig.class);

    @Autowired private ToolRegistry registry;
    @Autowired private DocumentService documentService;
    @Autowired private AgentToolPlannerService toolPlanner;
    @Autowired(required = false) private McpSearchService mcpSearch;
    @Autowired(required = false) private McpClientManager mcpManager;

    @Value("${mcp.servers:}") private String mcpServersConfig;

    @PostConstruct
    public void registerTools() {
        // ── 内置工具（注册时显式声明适用模式）──
        registry.register("getCurrentDateTime", "获取当前日期和时间",     ToolMode.ALL,  args -> LocalDateTime.now().toString());
        registry.register("calculate",          "执行数学计算",           ToolMode.ALL,  this::calc);
        registry.register("queryInternalDocs",  "搜索本项目知识库",       ToolMode.DEEP, documentService::searchAndFormat);
        registry.register("deep_research",      "多步调查分析（DEEP模式专用）", ToolMode.DEEP, args -> {
            String[] parts = args.split("\\|", 2);
            return toolPlanner.planAndExecute(parts[0], parts.length > 1 ? parts[1] : "调查步骤");
        });

        // ── 联网搜索（内置实现，不需要 MCP Server）──
        if (mcpSearch != null) registry.register("webSearch", "联网搜索最新信息", ToolMode.ALL, mcpSearch::search);

        // ── 外部 MCP Server 自动发现 ──
        log.info("[MCP] 配置: '{}'", mcpServersConfig);
        if (mcpManager != null && mcpServersConfig != null && !mcpServersConfig.isBlank()) {
            for (String entry : mcpServersConfig.split(",")) {
                String[] kv = entry.trim().split(":", 2);
                if (kv.length == 2) {
                    mcpManager.connect(kv[0].trim(), kv[1].trim());
                }
            }
            for (McpClient.McpTool tool : mcpManager.discoverAll()) {
                ToolMode.Parsed p = ToolMode.fromDescription(tool.description());
                registry.register(tool.name(), p.cleanDescription(), p.mode(), tool::execute);
            }
        }
    }

    private String calc(String args) {
        log.info("[Tool] calculate('{}')", args);
        try { return String.valueOf(eval(args.replaceAll("\"", "").trim())); }
        catch (Exception e) { return "计算失败: " + e.getMessage(); }
    }

    static double eval(String expr) { return new P(expr.replaceAll("\\s+", "")).parse(); }

    static class P {
        private final String e; private int i; private char c;
        P(String s) { e=s; i=-1; }
        void n() { c=(++i<e.length())?e.charAt(i):(char)-1; }
        boolean x(char ch) { if(c==ch){n();return true;} return false; }
        double parse() { n(); return expr(); }
        double expr() { double v=term(); while(true) { if(x('+')) v+=term(); else if(x('-')) v-=term(); else return v; } }
        double term() { double v=factor(); while(true) { if(x('*')) v*=factor(); else if(x('/')) v/=factor(); else if(x('%')) v%=factor(); else return v; } }
        double factor() { if(x('+')) return factor(); if(x('-')) return -factor(); int s=i; if(x('(')) { double v=expr(); x(')'); return v; } if((c>='0'&&c<='9')||c=='.') { while((c>='0'&&c<='9')||c=='.') n(); return Double.parseDouble(e.substring(s,i)); } throw new RuntimeException("? "+c); }
    }
}
