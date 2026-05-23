package com.vollocAI.ai.dao;

import com.vollocAI.ai.entity.User;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface UserDao {

    User queryById(Long id);

    List<User> queryAllByLimit(User user);

    int insert(User user);
}
