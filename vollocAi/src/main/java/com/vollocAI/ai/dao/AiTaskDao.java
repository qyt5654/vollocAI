package com.vollocAI.ai.dao;

import com.vollocAI.ai.entity.AiTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiTaskDao {

    AiTask queryById(Long id);

    AiTask queryByTaskId(@Param("taskId") String taskId);

    int insert(AiTask aiTask);

    int update(AiTask aiTask);

    int updateStatus(@Param("taskId") String taskId, @Param("status") String status);

    /** 查询超时未完成的任务（用于定时补偿） */
    List<AiTask> queryTimeoutPending(@Param("status") String status,
                                    @Param("deadline") LocalDateTime deadline);
}
