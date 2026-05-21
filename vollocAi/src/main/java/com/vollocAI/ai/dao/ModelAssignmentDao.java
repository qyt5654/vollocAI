package com.vollocAI.ai.dao;

import com.vollocAI.ai.entity.ModelAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelAssignmentDao {

    int insert(ModelAssignment assignment);

    int deleteByModelAndUser(@Param("modelId") Long modelId, @Param("userId") Long userId);

    int deleteByModelId(@Param("modelId") Long modelId);

    List<Long> findUserIdsByModelId(@Param("modelId") Long modelId);

    List<Long> findModelIdsByUserId(@Param("userId") Long userId);
}
