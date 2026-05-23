package com.vollocAI.ai.dao;

import com.vollocAI.ai.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionDao {

    ChatSession findBySessionId(@Param("sessionId") String sessionId);

    List<ChatSession> findByUserId(@Param("userId") Long userId);

    int insert(ChatSession session);

    int update(ChatSession session);

    int deleteBySessionId(@Param("sessionId") String sessionId);
}
