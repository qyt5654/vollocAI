package com.vollocAI.ai.service.Impl;

import com.vollocAI.ai.entity.User;
import com.vollocAI.ai.dao.UserDao;
import com.vollocAI.ai.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service("userService")
public class UserServiceImpl implements UserService {
    @Resource private UserDao userDao;

    @Override public User queryById(Long id) { return this.userDao.queryById(id); }

    @Override
    public User doLogin(User user) {
        List<User> users = this.userDao.queryAllByLimit(user);
        return users.isEmpty() ? null : users.get(0);
    }

    @Override public void doRegister(User user) { this.userDao.insert(user); }

    @Override public List<User> listAll() { return this.userDao.queryAllByLimit(new User()); }
}
