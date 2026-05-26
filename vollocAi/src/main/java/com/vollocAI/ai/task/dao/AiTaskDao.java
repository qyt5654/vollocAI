package com.vollocAI.ai.task.dao;

import com.vollocAI.ai.task.AiTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiTaskDao {

    AiTask queryByTaskId(@Param("taskId") String taskId);

    int insert(AiTask aiTask);

    int update(AiTask aiTask);

    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);
}
