package com.vollocAI.ai.dao;

import org.apache.ibatis.annotations.Mapper;
import com.vollocAI.ai.entity.DatabaseAi;
import java.util.List;


/**
 * (DatabaseAi)表数据库访问层
 *
 * @author makejava
 * @since 2025-04-23 15:02:59
 */
@Mapper
public interface DatabaseAiDao {

    /**
     * 通过ID查询单条数据
     *
     * @param id 主键
     * @return 实例对象
     */
    DatabaseAi queryById(Long id);

    int insert(DatabaseAi databaseAi);

    /**
     * 修改数据
     *
     * @param databaseAi 实例对象
     * @return 影响行数
     */
    int update(DatabaseAi databaseAi);

    /**
     * 通过主键删除数据
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 根据用户id查询所拥有的模型
     * @param databaseAi
     * @return
     */
    List<DatabaseAi> selectByDatabaseAi(DatabaseAi databaseAi);
}

