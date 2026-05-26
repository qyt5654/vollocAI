package com.vollocAI.ai.task;

import com.vollocAI.ai.task.dao.AiTaskDao;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class AiTaskService {

    @Resource private AiTaskDao aiTaskDao;

    public AiTask queryByTaskId(String taskId) { return aiTaskDao.queryByTaskId(taskId); }
    public int insert(AiTask aiTask) { return aiTaskDao.insert(aiTask); }
    public int update(AiTask aiTask) { return aiTaskDao.update(aiTask); }
    public int updateStatus(String taskId, String status) { return aiTaskDao.updateStatus(taskId, status); }
}
