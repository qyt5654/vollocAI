package com.vollocAI.ai.service;

import com.vollocAI.ai.entity.User;
import java.util.List;

public interface UserService {

    User queryById(Long id);

    User doLogin(User user);

    void doRegister(User user);

    List<User> listAll();
}
