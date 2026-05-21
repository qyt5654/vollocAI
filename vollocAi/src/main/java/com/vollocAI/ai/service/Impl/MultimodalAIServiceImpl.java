package com.vollocAI.ai.service.Impl;

import com.alibaba.cloud.ai.dashscope.api.DashScopeSpeechSynthesisApi;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisModel;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisOptions;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisPrompt;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisResponse;
import com.vollocAI.ai.service.MultimodalAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.UUID;

@Service
public class MultimodalAIServiceImpl implements MultimodalAIService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalAIServiceImpl.class);
    private static final String VOICE_FILE_PATH = "/Users/yipingchuan/voice/";

    @Override
    public String chat(String query, String apiKey, String apiUrl, String modelName) {
        OpenAiApi api = new OpenAiApi(apiUrl, apiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder().withModel(modelName).build();
        ChatModel model = new OpenAiChatModel(api, options);
        String content = model.call(new Prompt(new UserMessage(query)))
                .getResult().getOutput().getContent();
        logger.info("文本对话 [{}]: {}", modelName, content);
        return content;
    }

    @Override
    public String generateImage(String query, String apiKey, String apiUrl, String modelName) {
        OpenAiImageApi imageApi = new OpenAiImageApi(apiUrl, apiKey, RestClient.builder());
        OpenAiImageOptions options = OpenAiImageOptions.builder()
                .withN(1).withHeight(1024).withWidth(1024).build();
        OpenAiImageModel model = new OpenAiImageModel(imageApi);
        ImageResponse response = model.call(
                new ImagePrompt("生成图片，内容为：" + query, options)
        );
        String imageUrl = response.getResult().getOutput().getUrl();
        logger.info("图片生成 [{}]: {}", modelName, imageUrl);
        return imageUrl;
    }

    @Override
    public String generateVoice(String query, String apiKey, String apiUrl, String modelName) {
        DashScopeSpeechSynthesisApi api = new DashScopeSpeechSynthesisApi(apiKey, apiUrl);
        DashScopeSpeechSynthesisOptions options = DashScopeSpeechSynthesisOptions.builder()
                .withModel(modelName)
                .withSpeed(1.0)
                .withPitch(0.9)
                .withVolume(60)
                .build();
        DashScopeSpeechSynthesisModel model = new DashScopeSpeechSynthesisModel(api, options);

        SpeechSynthesisResponse response = model.call(
                new SpeechSynthesisPrompt("生成语音，内容为：" + query, options)
        );

        String filePath = VOICE_FILE_PATH + UUID.randomUUID() + "output.mp3";
        ByteBuffer byteBuffer = response.getResult().getOutput().getAudio();
        File outFile = new File(filePath);
        try {
            Files.write(outFile.toPath(), byteBuffer.array());
        } catch (IOException e) {
            logger.error("语音文件写入失败", e);
            return "语音生成失败: " + e.getMessage();
        }
        logger.info("语音生成 [{}]: {}", modelName, filePath);
        return filePath;
    }
}
