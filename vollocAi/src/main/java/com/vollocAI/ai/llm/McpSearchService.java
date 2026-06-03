package com.vollocAI.ai.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 联网搜索服务 —— 使用阿里云 DashScope API Key，调用博查 Search API。
 * <p>免费额度 100次/天，注册: https://open.bochaai.com</p>
 * <p>配置: application.yaml → bocha.api-key: your_key</p>
 */
@Service
public class McpSearchService {

    private static final Logger log = LoggerFactory.getLogger(McpSearchService.class);
    private static final String BOCHA_URL = "https://api.bochaai.com/v1/web-search";
    private final RestTemplate rest = new RestTemplate();

    @Value("${bocha.api-key:}") private String bochaKey;

    public String search(String query) {
        if (bochaKey == null || bochaKey.isBlank()) {
            return "{\"error\":\"未配置 bocha.api-key\",\"hint\":\"在 application.yaml 中添加: bocha.api-key: your_key\"}";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            body.put("count", 5);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + bochaKey);
            HttpEntity<String> req = new HttpEntity<>(JSON.toJSONString(body), headers);

            ResponseEntity<String> resp = rest.postForEntity(BOCHA_URL, req, String.class);
            JSONObject data = JSON.parseObject(resp.getBody());

            if (data.getInteger("code") != 200) {
                return "{\"error\":\"搜索失败\",\"msg\":\"" + data.getString("msg") + "\"}";
            }

            JSONObject info = data.getJSONObject("data");
            JSONObject webPages = info.getJSONObject("webPages");
            JSONArray values = webPages != null ? webPages.getJSONArray("value") : new JSONArray();
            JSONArray list = new JSONArray();
            for (int i = 0; values != null && i < values.size(); i++) {
                JSONObject r = values.getJSONObject(i);
                JSONObject item = new JSONObject();
                item.put("title", r.getString("name"));
                item.put("url", r.getString("url"));
                item.put("snippet", r.getString("summary"));
                list.add(item);
            }
            log.info("[webSearch] '{}' → {}条结果", query.length() > 40 ? query.substring(0,40)+"..." : query, list.size());
            return JSON.toJSONString(list);
        } catch (Exception e) {
            log.error("[webSearch] 失败", e);
            return "{\"error\":\"搜索失败: " + e.getMessage() + "\"}";
        }
    }
}
