package com.vollocAI.ai.memory.dao;

import com.vollocAI.ai.memory.model.MemorySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemorySummaryDao {

    MemorySummary findBySessionId(@Param("sessionId") String sessionId);

    List<MemorySummary> findByUserId(@Param("userId") Long userId,
                                     @Param("limit") int limit);

    int insert(MemorySummary summary);

    int update(MemorySummary summary);
}
