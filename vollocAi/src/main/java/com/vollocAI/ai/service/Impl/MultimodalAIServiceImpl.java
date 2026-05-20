package com.vollocAI.ai.service.Impl;

import com.alibaba.cloud.ai.dashscope.api.DashScopeImageApi;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisModel;
import com.alibaba.cloud.ai.dashscope.audio.DashScopeSpeechSynthesisOptions;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisPrompt;
import com.alibaba.cloud.ai.dashscope.audio.synthesis.SpeechSynthesisResponse;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageModel;
import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import com.vollocAI.ai.service.MultimodalAIService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MultimodalAIServiceImpl implements MultimodalAIService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalAIServiceImpl.class);
    private static final String VOICE_FILE_PATH = "/Users/yipingchuan/voice/";

    @Resource
    private DashScopeImageModel imageModel;

    @Resource
    private DashScopeSpeechSynthesisModel speechSynthesisModel;

    private final ChatClient chatClient;

    public MultimodalAIServiceImpl(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new MessageChatMemoryAdvisor(new InMemoryChatMemory()))
                .defaultOptions(DashScopeChatOptions.builder().withTopP(0.7).build())
                .build();
    }

    @Override
    public String chat(String sessionId, String query, List<Map<String, String>> history) {
        // ChatMemory：由 MessageChatMemoryAdvisor 按 conversationId 自动管理上下文，
        // 历史以结构化 Message（user/assistant 角色）传给模型，不走 system prompt 拼接
        String content = chatClient.prompt()
                .system(s -> s.text("你是一个专业的智能助手，可以获取当前时间。")
                        .text("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。"))
                .user(query)
                .advisors(a -> a.param(MessageChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId))
                .options(DashScopeChatOptions.builder().withFunctions(Set.of("getCurrentDateTime")).build())
                .call()
                .content();
        logger.info("文本对话结果: {}", content);
        return content;
    }

    @Override
    public String generateImage(String query, List<Map<String, String>> history) {
        String systemPrompt = buildSystemPromptImage(history);

        SystemMessage systemMessage = new SystemMessage(systemPrompt);
        PromptTemplate promptTemplate = new PromptTemplate("生成图片，内容为：{query}。");
        Message userMessage = promptTemplate.createMessage(Map.of("query", query));

        ImageResponse response = imageModel.call(
                new ImagePrompt(
                        List.of(systemMessage, userMessage).toString(),
                        DashScopeImageOptions.builder()
                                .withModel(DashScopeImageApi.DEFAULT_IMAGE_MODEL)
                                .withN(1)
                                .withHeight(1024)
                                .withWidth(1024).build()
                )
        );
        String imageUrl = response.getResult().getOutput().getUrl();
        logger.info("图片生成结果: {}", imageUrl);
        return imageUrl;
    }

    @Override
    public String generateVoice(String query, List<Map<String, String>> history) {
        String systemPrompt = buildSystemPromptVoice(history);

        SystemMessage systemMessage = new SystemMessage(systemPrompt);
        PromptTemplate promptTemplate = new PromptTemplate("生成语音，内容为：{query}。");
        Message userMessage = promptTemplate.createMessage(Map.of("query", query));

        SpeechSynthesisResponse response = speechSynthesisModel.call(
                new SpeechSynthesisPrompt(
                        List.of(systemMessage, userMessage).toString(),
                        DashScopeSpeechSynthesisOptions.builder()
                                .withSpeed(1.0)
                                .withPitch(0.9)
                                .withVolume(60)
                                .build()
                )
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
        logger.info("语音文件地址: {}", filePath);
        return filePath;
    }

    private String buildSystemPromptImage(List<Map<String, String>> history) {
        return buildSystem(new StringBuilder()
                .append("你是一个专业的图片生成智能助手，根据提示信息定向生成图片。\n"), history);
    }

    private String buildSystemPromptVoice(List<Map<String, String>> history) {
        return buildSystem(new StringBuilder()
                .append("你是一个专业的语音生成智能助手，根据提示信息定向生成语音。\n"), history);
    }

    private String buildSystem(StringBuilder systemPromptBuilder, List<Map<String, String>> history) {
        if (history != null && !history.isEmpty()) {
            systemPromptBuilder.append("--- 对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 对话历史结束 ---\n\n");
        }
        systemPromptBuilder.append("请基于以上对话历史，回答用户的新问题。");
        return systemPromptBuilder.toString();
    }
}
