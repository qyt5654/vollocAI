package com.vollocAI.ai.service.Impl;


import com.vollocAI.ai.dao.DatabaseAiDao;
import com.vollocAI.ai.entity.DatabaseAi;
import com.vollocAI.ai.service.DatabaseAiService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * (DatabaseAi)表服务实现类
 *
 * @author makejava
 * @since 2025-04-23 15:03:02
 */
@Service("databaseAiService")
public class DatabaseAiServiceImpl implements DatabaseAiService {
    @Resource
    private DatabaseAiDao databaseAiDao;

    /**
     * 通过ID查询单条数据
     *
     * @param id 主键
     * @return 实例对象
     */
    @Override
    public DatabaseAi queryById(Long id) {
        return this.databaseAiDao.queryById(id);
    }

    /**
     * 新增数据
     *
     * @param databaseAi 实例对象
     * @return 实例对象
     */
    @Override
    public int insert(DatabaseAi databaseAi) {
        return this.databaseAiDao.insert(databaseAi);
    }

    /**
     * 修改数据
     *
     * @param databaseAi 实例对象
     * @return 实例对象
     */
    @Override
    public int update(DatabaseAi databaseAi) {
        return this.databaseAiDao.update(databaseAi);
    }

    /**
     * 通过主键删除数据
     *
     * @param id 主键
     * @return 是否成功
     */
    @Override
    public boolean deleteById(Long id) {
        return this.databaseAiDao.deleteById(id) > 0;
    }

    @Override
    public List<DatabaseAi> selectByDatabaseAi(DatabaseAi databaseAi) {
        return this.databaseAiDao.selectByDatabaseAi(databaseAi);
    }
}
