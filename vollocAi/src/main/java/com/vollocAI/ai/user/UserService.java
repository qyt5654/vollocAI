package com.vollocAI.ai.user;

import com.vollocAI.ai.user.dao.UserDao;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    @Resource private UserDao userDao;

    public User queryById(Long id) { return userDao.queryById(id); }

    public User doLogin(User user) {
        List<User> users = userDao.queryAllByLimit(user);
        return users.isEmpty() ? null : users.get(0);
    }

    public void doRegister(User user) { userDao.insert(user); }

    public List<User> listAll() { return userDao.queryAllByLimit(new User()); }
}
