package com.vollocAI.ai.model;

import com.vollocAI.ai.model.dao.DatabaseAiDao;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DatabaseAiService {

    @Resource private DatabaseAiDao databaseAiDao;

    public DatabaseAi queryById(Long id) { return databaseAiDao.queryById(id); }
    public int insert(DatabaseAi databaseAi) { return databaseAiDao.insert(databaseAi); }
    public int update(DatabaseAi databaseAi) { return databaseAiDao.update(databaseAi); }
    public boolean deleteById(Long id) { return databaseAiDao.deleteById(id) > 0; }
    public List<DatabaseAi> selectByDatabaseAi(DatabaseAi databaseAi) { return databaseAiDao.selectByDatabaseAi(databaseAi); }
}
