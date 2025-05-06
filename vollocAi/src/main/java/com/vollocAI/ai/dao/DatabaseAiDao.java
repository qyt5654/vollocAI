package com.vollocAI.ai.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    /**
     * 统计总行数
     *
     * @param databaseAi 查询条件
     * @return 总行数
     */
    long count(DatabaseAi databaseAi);

    /**
     * 新增数据
     *
     * @param databaseAi 实例对象
     * @return 影响行数
     */
    int insert(DatabaseAi databaseAi);

    /**
     * 批量新增数据（MyBatis原生foreach方法）
     *
     * @param entities List<DatabaseAi> 实例对象列表
     * @return 影响行数
     */
    int insertBatch(@Param("entities") List<DatabaseAi> entities);

    /**
     * 批量新增或按主键更新数据（MyBatis原生foreach方法）
     *
     * @param entities List<DatabaseAi> 实例对象列表
     * @return 影响行数
     * @throws org.springframework.jdbc.BadSqlGrammarException 入参是空List的时候会抛SQL语句错误的异常，请自行校验入参
     */
    int insertOrUpdateBatch(@Param("entities") List<DatabaseAi> entities);

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

