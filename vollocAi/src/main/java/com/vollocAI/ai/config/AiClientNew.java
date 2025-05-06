package com.vollocAI.ai.config;

import com.vollocAI.ai.entity.QuestionBO;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class AiClientNew {


    public String sendMessage(QuestionBO questionBO) {

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(questionBO.getAiApiUrl())  // 你的 API 地址
                .apiKey(questionBO.getAiApiKey())              // 你的 API Key
                .modelName(questionBO.getAiApiModel())           // 比如 "gpt-3.5-turbo" 或 "your-custom-model"
                .build();

        String answer = model.generate(questionBO.getQuestion());

        System.out.println(answer);
        return answer;
    }

    public URI askImg(QuestionBO questionBO){
        ImageModel imageModel = OpenAiImageModel.builder()
                .baseUrl(questionBO.getAiApiUrl())  // 你的 API 地址
                .apiKey(questionBO.getAiApiKey())              // 你的 API Key
                .modelName(questionBO.getAiApiModel())           // 比如 "gpt-3.5-turbo" 或 "your-custom-model"
                .build();
        Response<Image> response = imageModel.generate(questionBO.getQuestion());
        System.out.println(response.content().url());
        return response.content().url();
    }

}
