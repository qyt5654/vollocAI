package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.agent.AgentToolPlannerService;
import com.vollocAI.ai.rag.DocumentService;
import com.vollocAI.ai.agent.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工具注册 —— 显式注册，只有这里列出的才暴露给 AI。
 */
@Configuration
public class ToolConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolConfig.class);

    @Autowired private ToolRegistry registry;
    @Autowired private DocumentService documentService;
    @Autowired private AgentToolPlannerService toolPlanner;

    @PostConstruct
    public void registerTools() {
        registry.register("getCurrentDateTime",    "获取当前日期和时间",     args -> LocalDateTime.now().toString());
        registry.register("calculate",             "执行数学计算",           this::calc);
        registry.register("queryPrometheusAlerts", "查询 Prometheus 告警",  this::alerts);
        registry.register("queryLogs",             "查询应用日志",           this::logs);
        registry.register("queryInternalDocs",     "搜索本项目知识库",       documentService::searchAndFormat);
        registry.register("deep_research",         "多步调查分析（DEEP模式专用）", args -> {
            // args 格式: "query|context"，split 后分别传入
            String[] parts = args.split("\\|", 2);
            return toolPlanner.planAndExecute(parts[0], parts.length > 1 ? parts[1] : "调查步骤");
        });
    }

    private String calc(String args) {
        log.info("[Tool] calculate('{}')", args);
        try { return String.valueOf(eval(args.replaceAll("\"", "").trim())); }
        catch (Exception e) { return "计算失败: " + e.getMessage(); }
    }

    private String alerts(String args) {
        log.info("[Tool] queryAlerts({})", args);
        return JSON.toJSONString(List.of(
            Map.of("alertname", "HighCPUUsage", "severity", "warning", "value", "92.5"),
            Map.of("alertname", "MemoryLeak",   "severity", "critical","value", "87.3")));
    }

    private String logs(String args) {
        log.info("[Tool] queryLogs({})", args);
        return JSON.toJSONString(List.of(
            Map.of("service", "order-service", "message", "OutOfMemoryError"),
            Map.of("service", "payment-service", "message", "ConnectionTimeout")));
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
