package com.vollocAI.ai.service;


import com.vollocAI.ai.entity.DatabaseAi;

import java.util.List;

/**
 * (DatabaseAi)表服务接口
 *
 * @author makejava
 * @since 2025-04-23 15:03:02
 */
public interface DatabaseAiService {

    /**
     * 通过ID查询单条数据
     *
     * @param id 主键
     * @return 实例对象
     */
    DatabaseAi queryById(Long id);

    /**
     * 新增数据
     *
     * @param databaseAi 实例对象
     * @return 实例对象
     */
    int insert(DatabaseAi databaseAi);

    /**
     * 修改数据
     *
     * @param databaseAi 实例对象
     * @return 实例对象
     */
    int update(DatabaseAi databaseAi);

    /**
     * 通过主键删除数据
     *
     * @param id 主键
     * @return 是否成功
     */
    boolean deleteById(Long id);

    /**
     * 根据信息查询所拥有的模型
     * @param databaseAi
     * @return
     */
    List<DatabaseAi> selectByDatabaseAi(DatabaseAi databaseAi);

}
