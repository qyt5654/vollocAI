package com.vollocAI.ai.MQConsumer;

import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.entity.AiTask;
import com.vollocAI.ai.entity.AiTaskMessage;
import com.vollocAI.ai.service.AiTaskService;
import com.vollocAI.ai.service.IntentRecognitionService;
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
 * 智能文字消费者 — 支持意图识别分流 + 分段更新任务状态
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "ai-task-topic-message", consumerGroup = "test-consumer-group2")
public class MQMessageTaskConsumer implements RocketMQListener<String> {

    @Resource
    private IntentRecognitionService intentRecognitionService;

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
            log.info("消费者收到消息 taskId:{}", taskId);

            // 1. 更新状态为 PROCESSING
            aiTaskService.updateStatus(taskId, AiTask.STATUS_PROCESSING);

            try {
                // 2. 意图识别
                IntentRecognitionService.IntentResult intentResult =
                        intentRecognitionService.recognize(aiTaskMessage.getMessage());
                log.info("意图识别: {} -> {}", taskId, intentResult.intent());

                // 落库 intent
                AiTask updateIntent = new AiTask();
                updateIntent.setTaskId(taskId);
                updateIntent.setIntent(intentResult.intent());
                aiTaskService.update(updateIntent);

                // 3. 路由执行
                String answer = switch (intentResult.intent()) {
                    case "image" -> multimodalAIService.generateImage(intentResult.content(), List.of());
                    case "voice" -> multimodalAIService.generateVoice(intentResult.content(), List.of());
                    default -> multimodalAIService.chat(taskId, intentResult.content(), List.of());
                };
                log.info("AI 生成完成 taskId:{} intent:{}", taskId, intentResult.intent());

                // 4. 结果写入 Redis + 更新 DB 为 COMPLETED
                redisTemplate.opsForValue().set("ai:result:" + taskId, answer, 10, TimeUnit.MINUTES);

                AiTask completed = new AiTask();
                completed.setTaskId(taskId);
                completed.setResult(answer);
                completed.setStatus(AiTask.STATUS_COMPLETED);
                aiTaskService.update(completed);

            } catch (Exception e) {
                log.error("任务执行失败 taskId:{}", taskId, e);
                aiTaskService.updateStatus(taskId, AiTask.STATUS_FAILED);
            }
        });
    }
}
