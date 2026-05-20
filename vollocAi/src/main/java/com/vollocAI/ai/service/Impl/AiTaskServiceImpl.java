package com.vollocAI.ai.service.Impl;

import com.vollocAI.ai.dao.AiTaskDao;
import com.vollocAI.ai.entity.AiTask;
import com.vollocAI.ai.service.AiTaskService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class AiTaskServiceImpl implements AiTaskService {

    @Resource
    private AiTaskDao aiTaskDao;

    @Override
    public AiTask queryByTaskId(String taskId) {
        return aiTaskDao.queryByTaskId(taskId);
    }

    @Override
    public int insert(AiTask aiTask) {
        return aiTaskDao.insert(aiTask);
    }

    @Override
    public int update(AiTask aiTask) {
        return aiTaskDao.update(aiTask);
    }

    @Override
    public int updateStatus(String taskId, String status) {
        return aiTaskDao.updateStatus(taskId, status);
    }
}
