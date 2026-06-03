package com.vollocAI.ai.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import reactor.core.publisher.FluxSink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** ReAct SSE 事件协议：[[STATE]] [[ACT]] [[OBS]] [[PLAN]] */
public final class ReactProtocol {

    static final String ACT = "[[ACT]]";
    static final String OBS = "[[OBS]]";
    static final String STATE = "[[STATE]]";
    static final String PLAN = "[[PLAN]]";

    private ReactProtocol() {}

    public static boolean isEvent(String token) {
        return token != null && !token.isEmpty()
                && (token.startsWith(ACT) || token.startsWith(OBS)
                || token.startsWith(STATE) || token.startsWith(PLAN));
    }

    public static void emit(FluxSink<String> sink, String prefix, Map<String, ?> payload) {
        String p = JSON.toJSONString(payload);
        p = p.replace('\n', ' ').replace('\r', ' ');
        if (p.length() > 800) p = p.substring(0, 800) + "...";
        sink.next(prefix + p + "\n");
    }

    public static void state(FluxSink<String> sink, String state, Integer step, String msg, Long planMs) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("state", state);
        if (step != null) o.put("step", step);
        if (msg != null && !msg.isBlank()) o.put("msg", msg);
        if (planMs != null) o.put("planMs", planMs);
        emit(sink, STATE, o);
    }

    public static String extractJson(String s) {
        if (s == null) return null;
        int start = s.indexOf('{');
        if (start < 0) return null;
        boolean inStr = false, esc = false;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }

    public static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    public static Map<String, Object> obs(int step, String tool, long ms, String output, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("tool", tool);
        m.put("ms", ms);
        m.put("output", Objects.toString(output, ""));
        if (error != null && !error.isBlank()) m.put("error", error);
        return m;
    }
}
