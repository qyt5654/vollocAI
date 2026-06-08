package com.vollocAI.ai.llm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeSpeechSynthesisApi;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisModel;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisOptions;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisPrompt;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisResponse;
import com.vollocAI.ai.llm.MultimodalAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.UUID;

/**
 * 多模态 AI 服务 —— 按请求动态创建模型客户端。
 * 每次调用根据传入的 apiKey/apiUrl/modelName 动态 new 客户端，不依赖单例 Bean。
 */
@Service
public class MultimodalAIServiceImpl implements MultimodalAIService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalAIServiceImpl.class);
    private static final String VOICE_FILE_PATH = "/Users/yipingchuan/voice/";

    @Override
    public String generateImage(String query, String apiKey, String apiUrl, String modelName) {
        return generateImageWithDashScope(query, apiKey);
    }

    private static final String DASHSCOPE_IMG_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String DASHSCOPE_TASK_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    private String generateImageWithDashScope(String query, String apiKey) {
        try {
            java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", "wanx-v1");
            java.util.LinkedHashMap<String, Object> input = new java.util.LinkedHashMap<>();
            input.put("prompt", query);
            body.put("input", input);
            java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("n", 1);
            params.put("size", "1024*1024");
            body.put("parameters", params);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(apiKey);
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.set("X-DashScope-Async", "enable"); // 异步模式: DashScope要求异步调用
            org.springframework.http.HttpEntity<?> request = new org.springframework.http.HttpEntity<>(body, headers);

            org.springframework.web.client.RestTemplate rest = new org.springframework.web.client.RestTemplate();
            org.springframework.http.ResponseEntity<String> resp = rest.postForEntity(DASHSCOPE_IMG_URL, request, String.class);
            logger.info("图片生成任务提交: {}", resp.getBody());
            com.alibaba.fastjson.JSONObject respJson = com.alibaba.fastjson.JSON.parseObject(resp.getBody());
            if (respJson.containsKey("code")) {
                return "图片生成失败: " + respJson.getString("code") + " - " + respJson.getString("message");
            }
            String taskId = respJson.getJSONObject("output").getString("task_id");

            // 轮询任务状态，最多等60秒
            for (int i = 0; i < 60; i++) {
                Thread.sleep(2000);
                org.springframework.http.HttpEntity<Void> taskReq = new org.springframework.http.HttpEntity<>(headers);
                org.springframework.http.ResponseEntity<String> taskResp = rest.exchange(DASHSCOPE_TASK_URL + taskId,
                        org.springframework.http.HttpMethod.GET, taskReq, String.class);
                com.alibaba.fastjson.JSONObject taskJson = com.alibaba.fastjson.JSON.parseObject(taskResp.getBody());
                String status = taskJson.getJSONObject("output").getString("task_status");
                logger.info("图片任务状态[{}/{}]: {}", i + 1, taskId, status);
                if ("SUCCEEDED".equals(status)) {
                    String url = taskJson.getJSONObject("output").getJSONArray("results").getJSONObject(0).getString("url");
                    logger.info("图片生成成功: {}", url);
                    return url;
                }
                if ("FAILED".equals(status)) {
                    return "图片生成失败: " + taskJson.getJSONObject("output").getString("message");
                }
            }
            return "图片生成超时，请稍后重试";
        } catch (Exception e) {
            logger.error("图片生成失败", e);
            return "图片生成失败: " + e.getMessage();
        }
    }

    @Override
    public String generateVoice(String query, String apiKey, String apiUrl, String modelName) {
        DashScopeSpeechSynthesisApi api = new DashScopeSpeechSynthesisApi(apiKey, apiUrl);
        DashScopeSpeechSynthesisModel model = new DashScopeSpeechSynthesisModel(api,
                DashScopeSpeechSynthesisOptions.builder().withModel(modelName)
                        .withSpeed(1.0).withPitch(0.9).withVolume(60).build());
        SpeechSynthesisResponse resp = model.call(
                new SpeechSynthesisPrompt("生成语音，内容为：" + query));
        String path = VOICE_FILE_PATH + UUID.randomUUID() + "output.mp3";
        try { Files.write(new File(path).toPath(), resp.getResult().getOutput().getAudio().array()); }
        catch (IOException e) { logger.error("语音写入失败", e); return "语音生成失败: " + e.getMessage(); }
        logger.info("语音生成 [{}]: {}", modelName, path);
        return path;
    }
}
