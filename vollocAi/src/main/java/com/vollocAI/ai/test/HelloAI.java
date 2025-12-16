package com.vollocAI.ai.test;

import com.google.common.collect.Lists;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.apache.commons.collections.MapUtils;
import cn.hutool.core.map.MapUtil;

import java.util.*;
import java.util.concurrent.TimeUnit;

/***
 * @projectName langchain4j-project-1
 * @packageName com.qjc.demo
 * @author qjc
 * @description TODO
 * @Email qjc1024@aliyun.com
 * @date 2024-10-12 10:00
 **/
public class HelloAI {

//    public static void main(String[] args) {
//
//        OpenAiChatModel model = OpenAiChatModel.builder()
//                .baseUrl("https://www.ggwk1.online/v1")  // 你的 API 地址
//                .apiKey("sk-ggLnerEAlP4aEV1FbIiYTiZWgnwptA3iLss6e0nOyDqKaYcW")              // 你的 API Key
//                .modelName("gpt-4-0613")           // 比如 "gpt-3.5-turbo" 或 "your-custom-model"
//                .build();
//
//        String answer = model.generate("帮我写一首小诗");
//
//        System.out.println(answer);
//
//    }
    //流式输出
//    public static void main(String[] args) {
//
//        StreamingChatLanguageModel model = OpenAiStreamingChatModel.builder()
//                .baseUrl("https://www.ggwk1.online/v1")
//                .apiKey("sk-ggLnerEAlP4aEV1FbIiYTiZWgnwptA3iLss6e0nOyDqKaYcW")
//                .modelName("gpt-4-0613")
//                .build();
//
//
//        model.generate("你好，你是谁？", new StreamingResponseHandler<AiMessage>() {
//            @Override
//            public void onNext(String token) {
//
//                System.out.println(token);
//
//                try {
//                    TimeUnit.SECONDS.sleep(1);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
//
//            }
//
//            @Override
//            public void onError(Throwable error) {
//                System.out.println(error);
//            }
//        });
//
//    }

    //ai生图
    public static void main(String[] args) {
//        ImageModel imageModel = OpenAiImageModel.builder()
//                .baseUrl("https://www.ggwk1.online/v1")
//                .apiKey("sk-ggLnerEAlP4aEV1FbIiYTiZWgnwptA3iLss6e0nOyDqKaYcW")
//                .modelName("gpt-4-0613")
//                .build();
//        Response<Image> response = imageModel.generate("一只兔子");
//        System.out.println(response.content().url());

        System.out.println(findAnagrams("cbaebabacd", "abc"));
    }

    /**
     * 输入：nums = [-1,0,1,2,-1,-4]
     * 输出：[[-1,-1,2],[-1,0,1]]
     * @return
     */
    private static List<Integer> findAnagrams(String s, String p) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        Map<String, Integer> map1 = new HashMap<String, Integer>();
        int num = 1;

        int sum = 0;
        for(int i=0;i<p.length();i++){
            char pt = p.charAt(i);
            Integer c = map.get("" + pt);
            sum+=c;
        }
        System.out.println(sum);
        List<Integer> list = new ArrayList<>();
        int nnm = 0;
        for(int i=0;i<s.length();i++){
            char pt = s.charAt(i);
            Integer c = map.get("" + pt);
            nnm+=c;
            if(i>p.length()-1){
                nnm-=map.get("" + s.charAt(i-p.length()));
            }
            if(nnm == sum && i>=p.length()-1){
                list.add(i-p.length()+1);
            }
        }
        return list;
    }
}

