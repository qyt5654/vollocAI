package com.vollocAI.ai.service;

import com.vollocAI.ai.entity.AiTask;

public interface AiTaskService {

    AiTask queryByTaskId(String taskId);

    int insert(AiTask aiTask);

    int update(AiTask aiTask);

    int updateStatus(String taskId, String status);
}
