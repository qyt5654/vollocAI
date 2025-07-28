package com.vollocAI.ai.MQConsumer;


import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.config.AiClientNew;
import com.vollocAI.ai.entity.AiTaskMessage;
import com.vollocAI.ai.entity.DatabaseAi;
import com.vollocAI.ai.entity.QuestionBO;
import com.vollocAI.ai.service.DatabaseAiService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 获取图片消费者
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "ai-task-topic-img", consumerGroup = "test-consumer-group1")
public class MQImgTaskConsumer implements RocketMQListener<String> {

    @Resource
    private DatabaseAiService databaseAiService;
    @Resource
    private AiClientNew aiClientNew;
    @Resource(name = "aiThreadPool")
    private ThreadPoolExecutor aiThreadPool;
    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public void onMessage(String s) {
        aiThreadPool.execute(()->{
            log.info("接收到询问主体：" + s);
            AiTaskMessage aiTaskMessage = JSON.parseObject(s, AiTaskMessage.class);
            DatabaseAi databaseAi = new DatabaseAi();
            databaseAi.setUserId(aiTaskMessage.getUserId()); // 直接使用父线程的 loginId
            databaseAi.setId(aiTaskMessage.getModelId());
            List<DatabaseAi> databaseAis = databaseAiService.selectByDatabaseAi(databaseAi);

            QuestionBO questionBO = new QuestionBO();
            questionBO.setQuestion(aiTaskMessage.getMessage());
            questionBO.setAiApiKey(databaseAis.get(0).getAiApiKey());
            questionBO.setAiApiUrl(databaseAis.get(0).getAiApiUrl());
            questionBO.setAiApiModel(databaseAis.get(0).getAiApiModel());
            String answer = aiClientNew.askImg(questionBO).toString();

            //将图片路径存入redis中
            redisTemplate.opsForValue().set("ai:result:" + aiTaskMessage.getTaskId(), answer, 10, TimeUnit.MINUTES);
        });


    }
}