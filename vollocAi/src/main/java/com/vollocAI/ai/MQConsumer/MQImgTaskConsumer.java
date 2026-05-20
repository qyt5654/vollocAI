package com.vollocAI.ai.MQConsumer;

import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.entity.AiTask;
import com.vollocAI.ai.entity.AiTaskMessage;
import com.vollocAI.ai.service.AiTaskService;
import com.vollocAI.ai.service.MultimodalAIService;
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
 * 图片生成消费者
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "ai-task-topic-img", consumerGroup = "test-consumer-group1")
public class MQImgTaskConsumer implements RocketMQListener<String> {

    @Resource
    private MultimodalAIService multimodalAIService;

    @Resource
    private AiTaskService aiTaskService;

    @Resource(name = "aiThreadPool")
    private ThreadPoolExecutor aiThreadPool;

    @Resource
    private RedisTemplate redisTemplate;

    @Override
    public void onMessage(String s) {
        aiThreadPool.execute(() -> {
            AiTaskMessage aiTaskMessage = JSON.parseObject(s, AiTaskMessage.class);
            String taskId = aiTaskMessage.getTaskId();
            log.info("图片消费者收到消息 taskId:{}", taskId);

            // 1. 更新状态为 PROCESSING
            aiTaskService.updateStatus(taskId, AiTask.STATUS_PROCESSING);

            try {
                // 2. 图片生成
                String imageUrl = multimodalAIService.generateImage(aiTaskMessage.getMessage(), List.of());
                log.info("图片生成完成 taskId:{} url:{}", taskId, imageUrl);

                // 3. 结果写入 Redis + 更新 DB
                redisTemplate.opsForValue().set("ai:result:" + taskId, imageUrl, 10, TimeUnit.MINUTES);

                AiTask completed = new AiTask();
                completed.setTaskId(taskId);
                completed.setResult(imageUrl);
                completed.setStatus(AiTask.STATUS_COMPLETED);
                aiTaskService.update(completed);

            } catch (Exception e) {
                log.error("图片任务执行失败 taskId:{}", taskId, e);
                aiTaskService.updateStatus(taskId, AiTask.STATUS_FAILED);
            }
        });
    }
}
