package com.vollocAI.ai.scheduler;

import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.dao.AiTaskDao;
import com.vollocAI.ai.entity.AiTask;
import com.vollocAI.ai.entity.AiTaskMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务补偿调度器：兜底 MQ 丢失 / 消费者假死导致的超时任务
 */
@Slf4j
@Component
public class TaskCompensationScheduler {

    /** PENDING 超时阈值（秒）*/
    private static final int PENDING_TIMEOUT_SECONDS = 60;
    /** PROCESSING 超时阈值（秒）*/
    private static final int PROCESSING_TIMEOUT_SECONDS = 120;

    @Resource
    private AiTaskDao aiTaskDao;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelay = 30_000)
    public void compensatePendingTasks() {
        LocalDateTime pendingDeadline = LocalDateTime.now().minusSeconds(PENDING_TIMEOUT_SECONDS);
        List<AiTask> pendingTasks = aiTaskDao.queryTimeoutPending(AiTask.STATUS_PENDING, pendingDeadline);

        for (AiTask task : pendingTasks) {
            log.warn("补偿 PENDING 任务: {} query={}", task.getTaskId(), task.getQuery());
            AiTaskMessage msg = new AiTaskMessage();
            msg.setTaskId(task.getTaskId());
            msg.setUserId(task.getUserId());
            msg.setMessage(task.getQuery());

            String topic = "image".equals(task.getIntent())
                    ? "ai-task-topic-img" : "ai-task-topic-message";
            rocketMQTemplate.convertAndSend(topic, JSON.toJSONString(msg));
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void compensateProcessingTasks() {
        LocalDateTime processingDeadline = LocalDateTime.now().minusSeconds(PROCESSING_TIMEOUT_SECONDS);
        List<AiTask> processingTasks = aiTaskDao.queryTimeoutPending(AiTask.STATUS_PROCESSING, processingDeadline);

        for (AiTask task : processingTasks) {
            log.warn("补偿 PROCESSING 任务: {} query={}", task.getTaskId(), task.getQuery());
            AiTaskMessage msg = new AiTaskMessage();
            msg.setTaskId(task.getTaskId());
            msg.setUserId(task.getUserId());
            msg.setMessage(task.getQuery());

            String topic = "image".equals(task.getIntent())
                    ? "ai-task-topic-img" : "ai-task-topic-message";
            rocketMQTemplate.convertAndSend(topic, JSON.toJSONString(msg));
        }
    }
}
