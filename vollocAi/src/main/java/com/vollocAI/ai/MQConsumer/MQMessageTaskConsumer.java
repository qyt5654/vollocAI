package com.vollocAI.ai.MQConsumer;

import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.entity.AiTask;
import com.vollocAI.ai.entity.AiTaskMessage;
import com.vollocAI.ai.entity.DatabaseAi;
import com.vollocAI.ai.service.AiTaskService;
import com.vollocAI.ai.service.DatabaseAiService;
import com.vollocAI.ai.service.IntentRecognitionService;
import com.vollocAI.ai.service.MultimodalAIService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AI 任务消费者 — 意图识别 → 模型选择 → 路由执行
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "ai-task-topic", consumerGroup = "ai-task-consumer-group")
public class MQMessageTaskConsumer implements RocketMQListener<String> {

    @Resource
    private IntentRecognitionService intentRecognitionService;

    @Resource
    private MultimodalAIService multimodalAIService;

    @Resource
    private AiTaskService aiTaskService;

    @Resource
    private DatabaseAiService databaseAiService;

    @Resource(name = "aiThreadPool")
    private ThreadPoolExecutor aiThreadPool;

    @Resource
    private RedisTemplate redisTemplate;

    /** 默认模型凭证（yaml 兜底） */
    @Value("${ai.api.key}")
    private String defaultApiKey;

    @Value("${ai.api.url}")
    private String defaultApiUrl;

    @Value("${ai.api.model}")
    private String defaultModel;

    @Override
    public void onMessage(String s) {
        aiThreadPool.execute(() -> {
            AiTaskMessage msg = JSON.parseObject(s, AiTaskMessage.class);
            String taskId = msg.getTaskId();
            log.info("消费者收到消息 taskId:{} modelId:{}", taskId, msg.getModelId());

            aiTaskService.updateStatus(taskId, AiTask.STATUS_PROCESSING);

            try {
                // 1. 意图识别
                IntentRecognitionService.IntentResult intentResult =
                        intentRecognitionService.recognize(msg.getMessage());
                log.info("意图识别: {} -> {}", taskId, intentResult.intent());

                AiTask updateIntent = new AiTask();
                updateIntent.setTaskId(taskId);
                updateIntent.setIntent(intentResult.intent());
                aiTaskService.update(updateIntent);

                // 2. 解析模型凭证（database_ai 优先，yaml 兜底）
                ModelCredentials cred = resolveCredentials(msg.getModelId());

                // 3. 路由执行
                String answer = switch (intentResult.intent()) {
                    case "image" -> multimodalAIService.generateImage(
                            intentResult.content(), cred.apiKey, cred.apiUrl, cred.model);
                    case "voice" -> multimodalAIService.generateVoice(
                            intentResult.content(), cred.apiKey, cred.apiUrl, cred.model);
                    default -> multimodalAIService.chat(
                            intentResult.content(), cred.apiKey, cred.apiUrl, cred.model);
                };
                log.info("AI 生成完成 taskId:{} intent:{}", taskId, intentResult.intent());

                // 4. Redis + DB
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

    /** 解析模型凭证：database_ai 优先，yaml 兜底 */
    private ModelCredentials resolveCredentials(Long modelId) {
        if (modelId != null && modelId != 0) {
            try {
                DatabaseAi config = databaseAiService.queryById(modelId);
                if (config != null) {
                    return new ModelCredentials(config.getAiApiKey(), config.getAiApiUrl(), config.getAiApiModel());
                }
            } catch (Exception e) {
                log.warn("模型配置查询失败 modelId:{}，降级 yaml 默认", modelId, e);
            }
        }
        return new ModelCredentials(defaultApiKey, defaultApiUrl, defaultModel);
    }

    private record ModelCredentials(String apiKey, String apiUrl, String model) {}
}
