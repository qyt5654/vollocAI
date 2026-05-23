package com.vollocAI.ai.service.Impl;

import com.alibaba.cloud.ai.dashscope.api.DashScopeSpeechSynthesisApi;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisModel;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisOptions;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisPrompt;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisResponse;
import com.vollocAI.ai.service.MultimodalAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiImageApi;
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
    public reactor.core.publisher.Flux<String> streamChat(String query, String apiKey,
                                                           String apiUrl, String modelName) {
        return reactor.core.publisher.Flux.defer(() -> {
            ChatModel model = buildModel(apiKey, apiUrl, modelName);
            return model.stream(new Prompt(new UserMessage(query)))
                    .map(r -> { String c = r.getResult().getOutput().getContent(); return c != null ? c : ""; })
                    .filter(s -> !s.isEmpty());
        });
    }

    @Override
    public reactor.core.publisher.Flux<String> streamChat(String query, String apiKey,
            String apiUrl, String modelName, List<Map<String, String>> history) {
        return reactor.core.publisher.Flux.defer(() -> {
            ChatModel model = buildModel(apiKey, apiUrl, modelName);
            List<Message> messages = new ArrayList<>();
            if (history != null) {
                logger.info("streamChat 带历史 {} 轮", history.size() / 2);
                for (Map<String, String> m : history) {
                    if ("user".equals(m.get("role")))
                        messages.add(new UserMessage(m.get("content")));
                    else if ("assistant".equals(m.get("role")))
                        messages.add(new AssistantMessage(m.get("content")));
                }
            }
            messages.add(new UserMessage(query));
            return model.stream(new Prompt(messages))
                    .map(r -> { String c = r.getResult().getOutput().getContent(); return c != null ? c : ""; })
                    .filter(s -> !s.isEmpty());
        });
    }

    @Override
    public String generateImage(String query, String apiKey, String apiUrl, String modelName) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(120));
        OpenAiImageApi api = new OpenAiImageApi(apiUrl, apiKey, RestClient.builder().requestFactory(factory));
        OpenAiImageModel model = new OpenAiImageModel(api);
        ImageResponse resp = model.call(new ImagePrompt("生成图片，内容为：" + query,
                OpenAiImageOptions.builder().withN(1).withHeight(1024).withWidth(1024).build()));
        String url = resp.getResult().getOutput().getUrl();
        logger.info("图片生成 [{}]: {}", modelName, url);
        return url;
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

    private ChatModel buildModel(String apiKey, String apiUrl, String modelName) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));
        RestClient.Builder rc = RestClient.builder().requestFactory(factory);
        return new OpenAiChatModel(new OpenAiApi(apiUrl, apiKey, rc, WebClient.builder()),
                OpenAiChatOptions.builder().withModel(modelName).build());
    }
}
