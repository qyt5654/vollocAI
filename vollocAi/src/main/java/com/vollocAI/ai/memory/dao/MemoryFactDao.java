package com.vollocAI.ai.memory.dao;

import com.vollocAI.ai.memory.model.MemoryFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MemoryFactDao {

    int insert(MemoryFact fact);

    /** 按 ID 查单条 */
    MemoryFact findById(@Param("id") Long id);

    /** 按会话 ID 查该会话内的所有事实 */
    List<MemoryFact> findBySessionId(@Param("sessionId") String sessionId);

    /** 按会话查全部 PREFERENCE 类型事实 */
    List<MemoryFact> findPreferencesBySession(@Param("sessionId") String sessionId);

    /** 按会话 + 关键词检索 */
    List<MemoryFact> searchBySession(@Param("sessionId") String sessionId,
                                     @Param("keyword") String keyword,
                                     @Param("limit") int limit);
}
